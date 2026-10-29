package com.sparta.ticket.reservation.booking.application.cancel;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.cancel.CancelTransactions.CancelStart;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.port.PaymentPort;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 예약 취소 유스케이스. 일부러 @Transactional을 붙이지 않는다. 환불 요청은 트랜잭션 밖에서 하고,
 * 앞뒤의 DB 변경은 CancelTransactions가 각자의 트랜잭션으로 한다.
 * 환불이 끝났는지는 기다리지 않는다. payment가 요청을 받으면 취소로 끝내고, 환불을 끝까지 처리하는 책임은 payment에 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancelService {

    private final CancelTransactions transactions;
    private final PaymentPort paymentPort;
    private final BookingMetrics metrics;

    /**
     * 확정한 예약을 취소한다. 환불 요청의 결과를 모르면 CANCELING으로 응답하고, 취소 확인 스케줄러가 마무리한다.
     * 이미 취소 중이거나 취소됐으면 환불을 다시 요청하지 않고 지금 상태를 돌려준다.
     */
    public ReservationResult cancel(UserContext user, Long reservationId) {
        CancelStart start = transactions.startCancel(user.userId(), reservationId);
        if (!start.started()) {
            return start.current();
        }
        refundAndFinish(reservationId, start.orderId());
        return transactions.read(reservationId);
    }

    /**
     * 환불을 요청하고, payment가 받았으면 취소를 확정한다. 확정했으면 true다.
     * 취소 시작과 취소 확인 스케줄러가 같은 orderId로 부른다. payment는 orderId 기준으로 멱등이라 두 번 와도 환불은 한 번이다.
     */
    public boolean refundAndFinish(Long reservationId, String orderId) {
        return switch (requestRefund(reservationId, orderId).result()) {
            case ACCEPTED -> finish(reservationId, orderId);
            case UNKNOWN -> false;
            case UNKNOWN_ORDER -> {
                metrics.unknownRefundOrder();
                log.error(
                        "payment가 모르는 orderId라 환불을 요청하지 못했다. 관리자 확인이 필요하다. reservationId={}, orderId={}",
                        reservationId,
                        orderId);
                yield false;
            }
        };
    }

    private RefundOutcome requestRefund(Long reservationId, String orderId) {
        try {
            return paymentPort.refund(orderId, RefundReason.USER_CANCEL);
        } catch (RuntimeException e) {
            log.warn("환불 요청의 결과를 모른다. 취소 확인 스케줄러가 마무리한다. reservationId={}, orderId={}", reservationId, orderId, e);
            return RefundOutcome.unknown();
        }
    }

    private boolean finish(Long reservationId, String orderId) {
        try {
            return transactions.finishCancel(reservationId);
        } catch (RuntimeException e) {
            // Note: payment는 환불 요청을 받았는데 취소 확정만 실패했다. 예약은 CANCELING 그대로이므로 오류 대신 그 상태로 답하고,
            // 취소 확인 스케줄러가 같은 orderId로 다시 요청해 받았음을 확인한 뒤 확정한다.
            log.error("환불 요청은 접수됐는데 취소 확정에 실패했다. reservationId={}, orderId={}", reservationId, orderId, e);
            return false;
        }
    }
}
