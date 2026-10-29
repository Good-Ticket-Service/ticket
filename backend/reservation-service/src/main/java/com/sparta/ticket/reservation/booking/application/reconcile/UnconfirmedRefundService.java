package com.sparta.ticket.reservation.booking.application.reconcile;

import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.port.PaymentPort;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundReason;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제는 성공했는데 좌석 때문에 확정하지 못한 예약을 환불하고 끝낸다. 그대로 두면 사용자는 돈을 냈는데 표가 없고,
 * 예약이 결제 중으로 남아 같은 회차를 다시 예매하지 못한다.
 * 결과 반영이 환불이 필요하다고 기록한 예약만 고른다. 기록은 커밋돼 있으므로 서버가 재시작돼도 이어서 처리한다.
 * 같은 orderId로 다시 요청해도 payment는 처음 사유를 지키고 지금 상태를 돌려주므로 환불은 한 번이다(#127).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnconfirmedRefundService {

    private final ReservationRepository reservationRepository;
    private final UnconfirmedRefundTransactions transactions;
    private final PaymentPort paymentPort;
    private final ChangeStamps changeStamps;

    /**
     * 마지막 요청에서 threshold가 지난 예약을 한 묶음 환불 요청하고, 취소로 끝낸 건수를 돌려준다.
     * threshold는 처리 중인 환불 요청을 또 보내지 않도록 환불 호출의 최대 대기보다 길어야 한다.
     * 시간 예산이 다 되면 다음 환불 요청을 시작하지 않는다. 이미 시작한 요청 하나만큼은 넘칠 수 있다.
     */
    public int refund(Duration threshold, int batchSize, Duration timeBudget) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        LocalDateTime checkedBefore = changeStamps.now().minus(threshold);
        int canceled = 0;
        for (Long reservationId : reservationRepository.findRefundRequiredIds(checkedBefore, batchSize)) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (refundOne(reservationId, checkedBefore)) {
                    canceled++;
                }
            } catch (RuntimeException e) {
                log.warn("확정하지 못한 결제의 환불에 실패했다. 다음 실행에서 다시 고른다. reservationId={}", reservationId, e);
            }
        }
        return canceled;
    }

    private boolean refundOne(Long reservationId, LocalDateTime checkedBefore) {
        Optional<String> orderId = transactions.claimPendingRefund(reservationId, checkedBefore);
        if (orderId.isEmpty()) {
            return false;
        }
        return switch (requestRefund(reservationId, orderId.get()).result()) {
            case ACCEPTED -> transactions.finishRefund(reservationId, orderId.get());
            case UNKNOWN -> false;
            case UNKNOWN_ORDER -> {
                // Note: 결제 성공을 확인한 orderId라 생기면 안 된다. 결제 중으로 남으므로 결제 중 체류 지표가 관리자 확인으로 이어진다.
                log.error(
                        "payment가 모르는 orderId라 확정하지 못한 결제를 환불하지 못했다. reservationId={}, orderId={}",
                        reservationId,
                        orderId.get());
                yield false;
            }
        };
    }

    private RefundOutcome requestRefund(Long reservationId, String orderId) {
        try {
            return paymentPort.refund(orderId, RefundReason.CONFIRM_FAILED);
        } catch (RuntimeException e) {
            log.warn(
                    "확정하지 못한 결제의 환불 결과를 모른다. 다음 실행에서 다시 요청한다. reservationId={}, orderId={}", reservationId, orderId, e);
            return RefundOutcome.unknown();
        }
    }
}
