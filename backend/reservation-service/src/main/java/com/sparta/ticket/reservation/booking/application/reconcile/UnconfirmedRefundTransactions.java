package com.sparta.ticket.reservation.booking.application.reconcile;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정하지 못한 결제의 환불 요청 앞뒤 트랜잭션 단위.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * 환불 요청은 트랜잭션 밖에서 해야 하므로 원격 호출을 하는 UnconfirmedRefundService와 다른 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class UnconfirmedRefundTransactions {

    private final ReservationRepository reservationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /**
     * 아직 환불을 기다리는 결제 중 예약이고 checkedBefore 이전에 요청했으면 요청 시각을 남기고(다음 차례에서 뒤로 간다) 환불할 orderId를
     * 돌려준다. 남기지 못했으면 다른 인스턴스가 먼저 골랐거나 그사이 끝난 것이라 비어 있다.
     * 환불 요청 전에 커밋해야, 요청이 실패해도 같은 예약을 계속 앞에서 고르지 않는다.
     */
    @Transactional
    public Optional<String> claimPendingRefund(Long reservationId, LocalDateTime checkedBefore) {
        return reservationRepository
                .findById(reservationId)
                .filter(reservation -> reservation.getStatus() == ReservationStatus.PAYMENT_IN_PROGRESS)
                .filter(reservation ->
                        reservationRepository.markRefundChecked(reservationId, changeStamps.now(), checkedBefore) == 1)
                .map(Reservation::getOrderId);
    }

    /** payment가 환불 요청을 받았으니 예약을 CANCELED로 끝내고 쿠폰을 돌려준다. 그사이 다른 경로가 먼저 끝냈으면 false다. */
    @Transactional
    public boolean finishRefund(Long reservationId, String orderId) {
        ChangeStamp stamp = changeStamps.stamp();
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || !reservation.isCurrentPayment(orderId)) {
            return false;
        }
        boolean canceled = transitionExecutor.cancelUnconfirmedAndRelease(
                reservationId,
                reservation.getSeatIds(),
                () -> reservationRepository.cancelUnconfirmed(reservationId, orderId, stamp));
        if (canceled) {
            metrics.unconfirmedRefunded();
        }
        return canceled;
    }
}
