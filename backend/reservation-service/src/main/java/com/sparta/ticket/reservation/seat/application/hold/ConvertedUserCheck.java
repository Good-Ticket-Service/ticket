package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.ReservationView;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * REDIS 모드에서 "예약으로 바꾼 적 있는" 회원이 다시 선점할 때만 DB를 한 번 읽어 매수 제한을 확인한다(#449).
 * 나머지 선점 시도는 DB를 읽지 않는다. 그 예약이 끝났으면(만료·해제·결제 실패 뒤 만료) 막지 않는다.
 *
 * Note: BookingFacade의 메서드는 모두 바깥 트랜잭션을 요구해(MANDATORY) 읽기 전용 트랜잭션을 연다. 잠금은 걸지 않는다.
 * 여기서 통과해도 결제하기 때 선점 트랜잭션의 매수 제한·부분 UNIQUE가 다시 막으므로, 읽은 뒤의 변화로 두 번째 예약이 생기지는 않는다.
 */
@Component
@RequiredArgsConstructor
class ConvertedUserCheck {

    private final BookingFacade bookingFacade;

    /**
     * @return 같은 좌석의 내 예약이 만료 전이면 그 예약(200으로 돌려줄 결과). 막을 예약이 없으면 empty(회원 표시를 지우고 다시 선점한다)
     * @throws BusinessException 만료 전 활성 예약이 있으면 409 PURCHASE_LIMIT_EXCEEDED
     */
    @Transactional(readOnly = true)
    public Optional<SeatHoldResult> check(
            Long userId, Long scheduleId, Long scheduleSeatId, int basePrice, LocalDateTime now) {
        Optional<ReservationView> ownHold = bookingFacade.findActiveHold(userId, scheduleId);
        if (ownHold.isPresent() && ownHold.get().holdExpiresAt().isAfter(now)) {
            ReservationView hold = ownHold.get();
            if (hold.scheduleSeatIds().contains(scheduleSeatId)) {
                return Optional.of(new SeatHoldResult(
                        hold.reservationId(), scheduleSeatId, hold.holdExpiresAt(), basePrice, false, null));
            }
            throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
        // 만료 시각이 지난 내 HOLD는 스케줄러가 아직 정리하지 않았어도 활성으로 보지 않는다. 결제하기 때 선점 트랜잭션 6단계가 정리한다
        int expiredOwnSeats = ownHold.map(view -> view.scheduleSeatIds().size()).orElse(0);
        if (bookingFacade.countActiveSeats(userId, scheduleId) - expiredOwnSeats >= 1) {
            throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
        return Optional.empty();
    }
}
