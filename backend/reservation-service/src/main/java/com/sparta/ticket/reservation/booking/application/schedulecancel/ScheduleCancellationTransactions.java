package com.sparta.ticket.reservation.booking.application.schedulecancel;

import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ScheduleCancellationRepository;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 취소 정리가 예약 한 건마다 여는 트랜잭션.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * 환불 요청은 트랜잭션 밖에서 해야 하므로 원격 호출을 하는 ScheduleCancellationService와 다른 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class ScheduleCancellationTransactions {

    private final ReservationRepository reservationRepository;
    private final ScheduleCancellationRepository cancellationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final ChangeStamps changeStamps;

    /**
     * 선점이면 끝내고 좌석과 쿠폰을 돌려준다. 확정이면 취소 중으로 바꾸고 환불을 요청할 orderId를 돌려준다.
     * 취소 중을 환불 요청 전에 커밋해야, 환불 결과를 모를 때도 취소 확인 스케줄러가 이 예약을 찾아 마무리한다.
     * 그사이 다른 경로가 먼저 바꿨으면 조건부 UPDATE가 0행이라 아무것도 하지 않고 비어 있다.
     */
    @Transactional
    public Optional<String> closeHoldOrStartRefund(Long reservationId) {
        ChangeStamp stamp = changeStamps.stamp();
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return Optional.empty();
        }
        return switch (reservation.getStatus()) {
            case HOLD -> {
                transitionExecutor.cancelHoldAndRelease(
                        reservationId,
                        reservation.getSeatIds(),
                        () -> reservationRepository.cancelHold(reservationId, stamp));
                yield Optional.empty();
            }
            case CONFIRMED ->
                reservationRepository.startScheduleCancel(reservationId, stamp) == 1
                        ? Optional.of(reservation.getOrderId())
                        : Optional.empty();
            case PAYMENT_IN_PROGRESS, CANCELING, EXPIRED, RELEASED, CANCELED -> Optional.empty();
        };
    }

    /** 받은 지 completeAfter가 지난 기록을 완료한다. 진행 중인 예약이 없는지는 부르는 쪽이 먼저 확인한다. */
    @Transactional
    public boolean complete(Long scheduleId, Duration completeAfter) {
        return cancellationRepository.complete(scheduleId, changeStamps.now().minus(completeAfter), changeStamps.now())
                == 1;
    }
}
