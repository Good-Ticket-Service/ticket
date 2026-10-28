package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과 확인 스케줄러가 payment 조회 앞에서 여는 트랜잭션 단위.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * payment 조회와 승인 재요청은 트랜잭션 밖에서 해야 하므로, 원격 호출을 하는 PaymentReconcileService와 다른 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class PaymentReconcileTransactions {

    private final ReservationRepository reservationRepository;
    private final ChangeStamps changeStamps;

    /** 결과를 확인할 결제 중 예약과 다시 보낼 승인 요청 값. */
    public record PendingPayment(Long reservationId, String orderId, String pgAuthToken, long amount) {}

    /** 결과 확인 스케줄러가 이 예약을 확인했다고 남긴다. 다음 차례에서 뒤로 간다. */
    @Transactional
    public void markPaymentChecked(Long reservationId) {
        reservationRepository.markPaymentChecked(reservationId, changeStamps.now());
    }

    /** 아직 결제 중이면 다시 보낼 값을 돌려준다. 그사이 다른 경로로 끝났으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<PendingPayment> readPendingPayment(Long reservationId) {
        return reservationRepository
                .findById(reservationId)
                .filter(reservation -> reservation.getStatus() == ReservationStatus.PAYMENT_IN_PROGRESS)
                .map(reservation -> new PendingPayment(
                        reservation.getId(),
                        reservation.getOrderId(),
                        reservation.getPgAuthToken(),
                        reservation.getPaymentAmount()));
    }
}
