package com.sparta.ticket.fakepg.application.service.toss;

import com.sparta.ticket.fakepg.application.service.approval.ConfirmDecision;
import com.sparta.ticket.fakepg.application.service.approval.ConfirmResult;
import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.application.service.fault.ResponseHoldException;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 토스 결제 API의 업무 규칙. 처리 한도·지연·5xx·응답 유실처럼 요청 단위로 거는 장애는 presentation의 실행기가 맡고,
 * 여기서는 업무 결과를 바꾸는 장애(승인 거절, 취소 거절, 지연 승인)만 다룬다.
 */
@Service
public class TossPaymentService {

    private final TossPaymentTransactions transactions;
    private final FaultInjector faults;
    private final TaskScheduler delayedApprovalScheduler;
    private final Clock clock;
    private final int authValidMinutes;

    public TossPaymentService(
            TossPaymentTransactions transactions,
            FaultInjector faults,
            TaskScheduler delayedApprovalScheduler,
            Clock clock,
            @Value("${fake-pg.auth-valid-minutes}") int authValidMinutes) {
        this.transactions = transactions;
        this.faults = faults;
        this.delayedApprovalScheduler = delayedApprovalScheduler;
        this.clock = clock;
        this.authValidMinutes = authValidMinutes;
    }

    /** 결제 인증 시뮬레이션. 브라우저 결제창에서 사용자가 인증을 마친 것처럼 paymentKey를 발급한다. */
    public PgTransaction authorize(String orderId, String orderName, int amount) {
        return transactions.authorize(orderId, orderName, amount, authValidMinutes);
    }

    public PgTransaction confirm(String paymentKey, String orderId, int amount) {
        ConfirmResult result = transactions.confirm(paymentKey, orderId, amount, decide());
        return switch (result.outcome()) {
            case APPROVED -> result.transaction();
            case REJECTED -> throw new PgException(PgErrorCode.REJECT_CARD_PAYMENT);
            case EXPIRED -> throw new PgException(PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
            case DELAYED -> {
                Instant approveAt = clock.instant().plusMillis(faults.delayedApprovalMs(PgProvider.TOSS));
                delayedApprovalScheduler.schedule(() -> transactions.approveLater(paymentKey), approveAt);
                throw new ResponseHoldException();
            }
        };
    }

    public PgTransaction cancel(String paymentKey, String cancelReason, Integer cancelAmount) {
        return transactions.cancel(
                paymentKey, cancelReason, cancelAmount, faults.shouldFail(PgProvider.TOSS, PgOperation.CANCEL));
    }

    public PgTransaction findByOrderId(String orderId) {
        return transactions.findByOrderId(orderId);
    }

    public PgTransaction findByPaymentKey(String paymentKey) {
        return transactions.findByPaymentKey(paymentKey);
    }

    private ConfirmDecision decide() {
        if (faults.shouldFail(PgProvider.TOSS, PgOperation.CONFIRM)) {
            return ConfirmDecision.REJECT;
        }
        if (faults.shouldDelayApproval(PgProvider.TOSS)) {
            return ConfirmDecision.DELAY;
        }
        return ConfirmDecision.APPROVE;
    }
}
