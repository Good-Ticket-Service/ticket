package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 결과를 예약에 반영하는 유일한 곳. Phase 1은 승인 응답이, Phase 2는 Kafka 리스너가, 결과 확인 스케줄러는 조회 결과가
 * 같은 이 코드를 부른다. 그래야 두 Phase의 측정 차이를 통신 방식 때문이라고 말할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResultApplier {

    private final ReservationRepository reservationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** 예약 상태가 바뀌었으면 true. 결과를 모르거나, 같은 결과가 다시 왔거나, 지난 시도의 결과면 false. */
    @Transactional
    public boolean apply(Long reservationId, String orderId, ConfirmOutcome outcome) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.warn("결제 결과의 예약이 없다. reservationId={}, orderId={}", reservationId, orderId);
            return false;
        }
        ChangeStamp stamp = changeStamps.stamp();
        return switch (outcome.result()) {
            case SUCCEEDED -> applySuccess(reservation, orderId, stamp);
            case FAILED -> applyFailure(reservation, orderId, stamp);
            case UNKNOWN -> false;
        };
    }

    /** 지금 시도의 결제 중 예약일 때만 좌석을 잠그고 확정한다. 그 밖의 성공은 좌석을 건드리지 않고 가린다. */
    private boolean applySuccess(Reservation reservation, String orderId, ChangeStamp stamp) {
        if (!reservation.isCurrentPayment(orderId)) {
            recordUnconfirmedSuccess(reservation, orderId, stamp);
            return false;
        }
        if (tryConfirm(reservation, orderId, stamp)) {
            return true;
        }
        recordUnconfirmedSuccess(
                reservationRepository.reload(reservation.getId()).orElseThrow(), orderId, stamp);
        return false;
    }

    /** 좌석을 잠그고 확정한다. 확정됐을 때만 티켓 코드를 발급하고 true. */
    private boolean tryConfirm(Reservation reservation, String orderId, ChangeStamp stamp) {
        Long id = reservation.getId();
        if (transitionExecutor.confirmAndSell(
                id, reservation.getSeatIds(), () -> reservationRepository.confirm(id, orderId, stamp))) {
            reservation.issueTicketCodes();
            return true;
        }
        return false;
    }

    /**
     * 확정하지 않은 성공을 지금 상태로 가린다. 같은 시도로 이미 확정됐으면 같은 성공이 다시 온 것이라 무시하고,
     * 그 밖에는 자동 환불 대상으로 센다. 확정 뒤 사용자가 취소했어도 같은 성공이다. 환불은 취소가 이미 요청했다.
     */
    private void recordUnconfirmedSuccess(Reservation current, String orderId, ChangeStamp stamp) {
        Long id = current.getId();
        if (current.wasConfirmedBy(orderId)) {
            return;
        }
        // Note: 확정하지 못한 지금 시도는 결제 중으로 남아 결과 확인이 같은 성공을 계속 다시 받는다.
        // 처음 한 번만 환불 대상으로 세야 환불할 결제 수가 부풀지 않는다.
        if (current.isCurrentPayment(orderId)
                && reservationRepository.markRefundRequired(id, orderId, stamp.at()) == 0) {
            return;
        }
        metrics.lateSuccess();
        log.warn(
                "결제는 됐는데 표가 없다. 자동 환불 대상이다. reservationId={}, orderId={}, status={}, currentOrderId={}",
                id,
                orderId,
                current.getStatus(),
                current.getOrderId());
    }

    /** 같은 실패가 다시 왔거나 지난 시도의 실패면 아무것도 바꾸지 않는다. */
    private boolean applyFailure(Reservation reservation, String orderId, ChangeStamp stamp) {
        if (!reservation.isCurrentPayment(orderId)) {
            return false;
        }
        Long id = reservation.getId();
        if (reservation.isHoldExpiredAt(stamp.at())) {
            return transitionExecutor.expireAndRelease(
                    id, reservation.getSeatIds(), () -> reservationRepository.expireAfterFailure(id, orderId, stamp));
        }
        return reservationRepository.returnToHold(id, orderId, stamp) == 1;
    }
}
