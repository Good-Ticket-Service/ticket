package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.HoldTarget;
import com.sparta.ticket.reservation.booking.application.facade.ReservationState;
import com.sparta.ticket.reservation.booking.application.facade.ReservationView;
import com.sparta.ticket.reservation.booking.application.facade.SeatLine;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.SeatChangeStamps;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 선점의 DB 변경을 한 트랜잭션에서 한다. 판매 검증이 끝난 뒤 {@link SeatHoldService}가 부른다.
 *
 * 잠금 순서: 좌석(id 오름차순) → 예약. booking의 결제 확정·만료 스케줄러·선점 해제도 같은 순서로 잠가 서로 교착하지 않는다.
 *   - 함께 잠그는 좌석: 요청 좌석, 이 회원의 HOLD 예약 좌석, 요청 좌석을 잡고 있던 만료 선점 예약의 좌석 전체.
 *     좌석을 나중에 따로 잠그면 그 예약을 정리하는 만료 스케줄러(그 예약 좌석을 오름차순으로 잠금)와 교착할 수 있어 처음에 한 번에 잠근다.
 *   - 예약은 좌석을 잠근 뒤 BookingFacade로만 바꾼다(expireIfDue·hold). 여러 예약을 정리할 때는 예약 ID 오름차순으로 부른다.
 *
 * 옛 예약 정리: 다른 회원의 만료 선점을 가져올 때 그 예약의 좌석을 요청 좌석만이 아니라 모두 반환한다.
 * expireIfDue는 예약만 EXPIRED로 바꾸므로, 일부 좌석만 반환하면 나머지 좌석이 끝난 예약의 HOLD로 남아 영원히 선점할 수 없게 된다(PR #85 리뷰).
 * 예약의 좌석 목록은 만든 뒤 바뀌지 않아(#148 예매 담당 답), 잠그기 전에 잠금 없이 읽은 목록을 그대로 잠금 대상으로 쓴다.
 * 만료 스케줄러가 같은 예약을 동시에 정리하면, 스케줄러는 좌석 잠금을 기다린 뒤 예약 UPDATE가 0행이 되어 좌석을 건드리지 않고 끝난다.
 *
 * Note: 좌석은 잠근 Entity의 전이 메서드(release·hold)로만 바꾼다. 같은 트랜잭션에서 조건부 일괄 UPDATE와 섞으면 영속성 컨텍스트의
 * 좌석 Entity가 바뀌기 전 값으로 남아 이후 판단이 틀어진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldTransactions {

    /** 선점 시간. Notion API 명세서 「좌석 선점」의 계약값(선점 시각 + 10분)이라 설정이 아니라 상수로 둔다. */
    static final Duration HOLD_DURATION = Duration.ofMinutes(10);

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final BookingFacade bookingFacade;
    private final SeatChangeStamps changeStamps;

    @Transactional
    public SeatHoldResult hold(Long userId, Long scheduleId, SeatHoldTarget target, SaleValidation validation) {
        // 1. 함께 잠글 좌석을 고른다. 예약만 읽고 좌석 Entity는 아직 읽지 않는다(잠그기 전에 읽으면 낡은 값이 남는다).
        //    이 회원의 HOLD와 요청 좌석을 잡고 있던 예약은 만료 여부와 관계없이 좌석 전체를 잠근다. 판매 검증을 기다리는 사이 만료될 수
        //    있어서, 지금 만료 전이라고 빼 두면 잠근 뒤 만료됐을 때 그 예약의 다른 좌석을 정리할 수 없다
        Optional<ReservationView> ownHold = bookingFacade.findActiveHold(userId, scheduleId);
        Long ownHoldId = ownHold.map(ReservationView::reservationId).orElse(null);
        Optional<ReservationView> previousHolder = previousHolder(target, ownHoldId);
        TreeSet<Long> lockIds = new TreeSet<>();
        lockIds.add(target.scheduleSeatId());
        ownHold.ifPresent(view -> lockIds.addAll(view.scheduleSeatIds()));
        previousHolder.ifPresent(view -> lockIds.addAll(view.scheduleSeatIds()));

        // 2. 한 번에 id 오름차순으로 잠근다. 이 트랜잭션에서 처음 읽는 좌석이라 결과가 DB의 최신 값이다
        Map<Long, ScheduleSeat> locked = scheduleSeatRepository.findAllByIdsForUpdate(lockIds).stream()
                .collect(Collectors.toMap(ScheduleSeat::getId, Function.identity()));
        ScheduleSeat seat = locked.get(target.scheduleSeatId());
        if (seat == null) {
            // 1단계 조회 뒤 좌석이 논리 삭제됐다
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND);
        }

        // 판정과 만료 시각 계산에 쓰는 현재 시각은 잠근 뒤에 읽는다. 판매 검증·잠금 대기 동안 지난 시간을 반영해야 그 사이 만료된
        // 선점을 만료로 본다. booking의 만료 조건(hold_expires_at <= :now)도 같은 공용 Clock에서 읽는다
        LocalDateTime now = changeStamps.now();

        // 3. 같은 회원·같은 좌석의 만료 전 선점이면 새로 만들지 않고 기존 예약을 돌려준다(200). 잠근 좌석 상태로 판단한다.
        //    잠그기 전에 읽은 내 HOLD(ownHoldId)뿐 아니라, 잠금을 기다리는 사이 같은 회원의 다른 요청(더블 클릭·재시도)이 만든
        //    예약일 수도 있어 다시 읽어 확인한다. 잠근 뒤의 조회는 READ COMMITTED라 그 요청의 커밋을 본다
        if (seat.getStatus() == SeatStatus.HOLD && !seat.isHoldExpiredAt(now)) {
            Long holderId = seat.getReservationId();
            boolean mine = seat.isHeldBy(ownHoldId)
                    || bookingFacade
                            .getReservation(holderId)
                            .filter(view -> view.userId().equals(userId) && view.state() == ReservationState.HOLD)
                            .isPresent();
            if (mine) {
                return new SeatHoldResult(holderId, seat.getId(), seat.getHoldExpiresAt(), seat.getBasePrice(), false);
            }
        }

        // 4. 좌석 자체 조건. 판매 검증은 공연 기준이고, 좌석 서비스가 가진 스냅샷이 그 결과와 같은지 여기서 확인한다
        if (!seat.isSellable()) {
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_SELLABLE);
        }
        int validatedPrice = validation.seats().get(0).basePrice();
        if (seat.getConfigVersion() != validation.configVersion()
                || !seat.getBasePrice().equals(validatedPrice)) {
            throw new BusinessException(ReservationErrorCode.SEAT_CONFIG_VERSION_MISMATCH);
        }

        // 5. 요청 좌석을 다른 예약이 잡고 있으면, 그 선점이 지금 만료됐고 1단계에서 본 예약과 같을 때만 가져올 수 있다.
        //    1단계 뒤에 다른 예약이 새로 잡았다면 그 선점은 판매 검증 시간만큼밖에 안 됐으므로 만료됐을 리 없다. 409가 맞다
        Long otherHolderId = null;
        if (seat.getReservationId() != null && !seat.isHeldBy(ownHoldId)) {
            boolean sameAsRead = previousHolder
                    .map(view -> seat.isHeldBy(view.reservationId()))
                    .orElse(false);
            if (!seat.isHoldExpiredAt(now) || !sameAsRead) {
                // 유효한 선점·판매 완료이거나, 읽은 뒤 다른 예약이 잡아 그 예약의 좌석을 함께 잠그지 못했다
                throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
            }
            otherHolderId = seat.getReservationId();
        }

        // 6. 만료된 선점을 정리한다. 이 회원의 만료 선점과 요청 좌석의 옛 예약을 예약 ID 오름차순으로 만료시키고,
        //    만료에 성공하면 그 예약이 가진 좌석을 모두 반환한다
        Long ownExpiredId = ownHold.filter(view -> !view.holdExpiresAt().isAfter(now))
                .map(ReservationView::reservationId)
                .orElse(null);
        TreeSet<Long> toExpire = Stream.of(ownExpiredId, otherHolderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        for (Long reservationId : toExpire) {
            // expireIfDue는 HOLD이고 만료됐을 때만 EXPIRED로 바꾼다. 결제가 시작됐으면 false라 좌석을 건드리지 않는다
            if (bookingFacade.expireIfDue(reservationId)) {
                locked.values().stream()
                        .filter(lockedSeat -> lockedSeat.isHeldBy(reservationId))
                        .forEach(lockedSeat -> lockedSeat.release(reservationId));
                continue;
            }
            warnIfOrphanSeats(reservationId, locked.values());
            if (reservationId.equals(otherHolderId)) {
                // 만료 시각이 지났지만 그 예약은 결제 중이다. 결제 중인 예약의 좌석은 선택할 수 없다
                throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
            }
            // 이 회원의 예약이 false면 그 사이 결제가 시작된 것이다. 아래 매수 제한에서 409가 된다
        }

        // 7. 회원·회차당 1장. 정리한 예약은 EXPIRED라 세지 않는다. 동시에 들어온 요청은 부분 UNIQUE가 마지막으로 막는다
        if (bookingFacade.countActiveSeats(userId, scheduleId) >= 1) {
            throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }

        // 8. 예약을 만들고 좌석을 잡는다. 만료 시각은 한 번 계산해 예약과 좌석에 같은 값을 쓴다. 가격은 좌석의 스냅샷 가격이다.
        //    공연·판매자(#86)와 회차 시작 시각·공연명(#126)은 클라이언트 입력이 아니라 판매 검증 응답 값을 그대로 넘긴다
        LocalDateTime holdExpiresAt = now.plus(HOLD_DURATION);
        HoldTarget holdTarget = new HoldTarget(
                scheduleId,
                validation.performanceId(),
                validation.sellerId(),
                validation.scheduleStartAt(),
                validation.performanceTitle());
        Long reservationId = bookingFacade.hold(
                userId, holdTarget, List.of(new SeatLine(seat.getId(), seat.getBasePrice())), holdExpiresAt);
        seat.hold(reservationId, holdExpiresAt, now);
        return new SeatHoldResult(reservationId, seat.getId(), holdExpiresAt, seat.getBasePrice(), true);
    }

    /**
     * 1단계에서 요청 좌석을 다른 예약이 HOLD로 잡고 있었으면 그 예약을 읽는다. 좌석 목록을 함께 잠그기 위해서다.
     * 만료 여부는 여기서 보지 않는다(잠근 뒤 판정). 이 회원 자신의 예약은 이미 함께 잠그므로 다시 읽지 않는다.
     * 예약의 좌석 목록은 만든 뒤 바뀌지 않아 잠금 없이 읽은 값을 그대로 쓴다(#148 예매 담당 답).
     */
    private Optional<ReservationView> previousHolder(SeatHoldTarget target, Long ownHoldId) {
        Long heldBy = target.heldReservationId();
        if (heldBy == null || heldBy.equals(ownHoldId)) {
            return Optional.empty();
        }
        return bookingFacade.getReservation(heldBy);
    }

    /**
     * expireIfDue가 false였는데, 잠근 좌석 중 그 예약이 아직 HOLD로 잡은 좌석이 있고 그 예약이 결제 중도 아니면 경고를 남긴다.
     * 예약은 이미 끝났는데(EXPIRED·RELEASED·CANCELED) 좌석만 그 예약의 HOLD로 남은 상태(고아 좌석)라는 뜻이다. 지금은 예약과
     * 좌석을 같은 트랜잭션에서 바꾸므로 생기지 않아야 한다. 좌석을 자동으로 풀면 상태를 잘못 분류할 때 판 좌석을 다시 팔 위험이
     * 있어, 로그로 알리기만 하고 409는 그대로 둔다(동시성 리뷰 4번).
     *
     * Note: 잡은 좌석이 없으면 경고하지 않는다. 만료 스케줄러가 먼저 정리해 이미 좌석을 돌려준 정상 경우다.
     * 결제 중인지는 findPaymentInProgress로 DB에서 다시 확인한다. 이 트랜잭션에서 먼저 읽은 예약 Entity는 그 뒤의
     * 조건부 UPDATE(결제 시작·만료)를 반영하지 못한 옛 상태일 수 있어 판단에 쓰지 않는다.
     */
    private void warnIfOrphanSeats(Long reservationId, Collection<ScheduleSeat> lockedSeats) {
        List<Long> stillHeld = lockedSeats.stream()
                .filter(lockedSeat -> lockedSeat.isHeldBy(reservationId))
                .map(ScheduleSeat::getId)
                .toList();
        if (stillHeld.isEmpty()
                || !bookingFacade.findPaymentInProgress(List.of(reservationId)).isEmpty()) {
            return;
        }
        log.warn(
                "만료된 선점의 예약을 만료시키지 못했는데 결제 중이 아니다. 좌석만 남은 상태일 수 있다. reservationId={}, scheduleSeatIds={}",
                reservationId,
                stillHeld);
    }
}
