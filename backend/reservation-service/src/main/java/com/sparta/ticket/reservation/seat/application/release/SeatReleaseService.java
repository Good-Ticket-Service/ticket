package com.sparta.ticket.reservation.seat.application.release;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.ReleaseResult;
import com.sparta.ticket.reservation.booking.application.facade.ReservationView;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 선점 해제(DELETE /api/seats/schedules/{scheduleId}/holds/{reservationId}). 결제 전 사용자가 잡은 좌석을 직접 놓는다.
 * 1인 1장 매수 제한이 있어, 해제가 없으면 다른 좌석으로 바꾸려는 사용자가 선점 시간(10분)이 지날 때까지 기다려야 한다.
 *
 * 처리는 트랜잭션 하나다. 원격 호출이 없어 선점처럼 트랜잭션 밖 단계를 나누지 않는다.
 *   1. 예약을 잠금 없이 읽어 없음·다른 회차(404)·남의 예약(403)을 가린다
 *   2. 예약의 좌석을 id 오름차순으로 한 번에 잠근다
 *   3. 예약 상태는 booking이 정한다(BookingFacade.release, 쿠폰 반환 포함)
 *   4. 결과가 끝난 예약이면 이 예약이 아직 잡은 좌석만 반환한다
 *
 * 잠금 순서: 좌석(id 오름차순) → 예약. release는 예약 행만 조건부 UPDATE로 잠근다. 예약을 먼저 잠그면 좌석을 먼저 잠그고 예약을
 * 바꾸는 선점·결제 확정·만료 스케줄러와 서로 기다려 교착한다(PR #153 동시성 리뷰 5번).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseService {

    private final BookingFacade bookingFacade;
    private final ScheduleSeatRepository scheduleSeatRepository;

    @Transactional
    public void release(Long userId, Long scheduleId, Long reservationId) {
        // 1. 예약을 읽는다. getReservation은 MANDATORY라 이 트랜잭션 안에서 부른다. 이미 끝난 예약에는 좌석이 남아 있지 않을 수
        //    있어 회차 확인은 좌석이 아니라 예약으로 한다. 다른 회차 예약을 404로 막아 경로의 회차와 다른 예약을 다루지 않게 한다
        ReservationView reservation = bookingFacade
                .getReservation(reservationId)
                .filter(view -> view.scheduleId().equals(scheduleId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.userId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 2. 예약의 좌석을 한 번에 id 오름차순으로 잠근다. 예약의 좌석 목록은 만든 뒤 바뀌지 않아 잠금 없이 읽은 값을 그대로 쓴다(#148).
        //    이 트랜잭션에서 좌석 Entity를 처음 읽는 시점이라 잠근 결과가 DB의 최신 값이다
        List<ScheduleSeat> locked = scheduleSeatRepository.findAllByIdsForUpdate(reservation.scheduleSeatIds());

        // 3. 예약 상태를 바꾼다. 만료 전 HOLD면 RELEASED, 만료 후 HOLD면 EXPIRED로 booking이 조건부 변경하고 쿠폰도 돌려준다.
        //    결제 시작과 동시에 와도 둘 다 status = HOLD 조건부 UPDATE라 한쪽만 성공한다
        ReleaseResult result = bookingFacade.release(userId, reservationId);
        if (result == ReleaseResult.NOT_RELEASABLE) {
            // 결제 중·확정·취소 중·취소된 예약이다. 좌석을 풀면 결제 중이거나 판 좌석을 다른 사람에게 다시 팔게 되므로 그대로 둔다
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        }

        // 4. RELEASED·EXPIRED·ALREADY_CLOSED: 이 예약이 아직 잡은 좌석만 반환한다. 다른 예약에 넘어간 좌석은 건드리지 않는다.
        //    ALREADY_CLOSED(이미 RELEASED·EXPIRED)에 좌석이 남아 있으면 함께 푼다. booking이 끝난 예약이라고 확정해 준 경우라
        //    결제 중 좌석을 잘못 풀 위험이 없다(선점에서 끝난 예약을 추측해 풀지 않는 것과 다르다)
        //    반환은 HOLD 좌석만 한다. 끝난 선점의 좌석이 SOLD면 예약과 좌석이 어긋난 상태라, 판 좌석을 다시 팔지 않도록 풀지 않고 알린다
        List<ScheduleSeat> held =
                locked.stream().filter(seat -> seat.isHeldBy(reservationId)).toList();
        held.stream().filter(seat -> seat.getStatus() == SeatStatus.HOLD).forEach(seat -> seat.release(reservationId));
        List<Long> sold = held.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.SOLD)
                .map(ScheduleSeat::getId)
                .toList();
        if (!sold.isEmpty()) {
            log.warn(
                    "끝난 선점의 좌석이 SOLD로 남아 있어 반환하지 않았다. 예약과 좌석 상태가 어긋났다. reservationId={}, result={}, scheduleSeatIds={}",
                    reservationId,
                    result,
                    sold);
        }
    }
}
