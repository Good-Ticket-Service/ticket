package com.sparta.ticket.fakepg.application.service.toss;

import com.sparta.ticket.fakepg.application.service.approval.ConfirmDecision;
import com.sparta.ticket.fakepg.application.service.approval.ConfirmOutcome;
import com.sparta.ticket.fakepg.application.service.approval.ConfirmResult;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import com.sparta.ticket.fakepg.domain.repository.PgTransactionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토스 거래의 상태를 바꾸는 트랜잭션 단위. 거래 행을 잠근 뒤 판단해, 같은 거래의 승인·취소가 동시에 와도 한 번만 처리한다.
 *
 * Note: 거절·만료처럼 "상태를 바꾸고 오류로 응답"하는 경우, 여기서 예외를 던지면 바꾼 상태까지 롤백된다.
 * 그래서 상태는 커밋하고 결과(Outcome)만 돌려준 뒤, 오류 응답은 호출한 쪽이 커밋 후에 만든다.
 */
@Component
@RequiredArgsConstructor
public class TossPaymentTransactions {

    private final PgTransactionRepository repository;
    private final Clock clock;

    @Transactional
    public PgTransaction authorize(String orderId, String orderName, int amount, int validMinutes) {
        LocalDateTime now = now();
        return repository
                .lockByProviderAndOrderId(PgProvider.TOSS, orderId)
                .map(existing -> {
                    existing.reauthorize(amount, now, validMinutes);
                    return existing;
                })
                .orElseGet(() -> repository.saveAndFlush(
                        PgTransaction.authorize(PgProvider.TOSS, orderId, orderName, amount, now, validMinutes)));
    }

    @Transactional
    public ConfirmResult confirm(String paymentKey, String orderId, int amount, ConfirmDecision decision) {
        LocalDateTime now = now();
        PgTransaction transaction = repository
                .lockByPaymentKey(paymentKey)
                .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYMENT_SESSION));
        transaction.verifyConfirmable(orderId, amount);
        if (transaction.isAuthExpired(now)) {
            transaction.expire(now);
            return new ConfirmResult(transaction, ConfirmOutcome.EXPIRED);
        }
        return switch (decision) {
            case APPROVE -> {
                transaction.approve(now);
                yield new ConfirmResult(transaction, ConfirmOutcome.APPROVED);
            }
            case REJECT -> {
                transaction.abort(PgErrorCode.REJECT_CARD_PAYMENT.name(), now);
                yield new ConfirmResult(transaction, ConfirmOutcome.REJECTED);
            }
            case DELAY -> new ConfirmResult(transaction, ConfirmOutcome.DELAYED);
        };
    }

    /** 지연 승인의 나중 기록. 그사이 인증이 만료됐거나 다른 처리가 끝났으면 아무것도 하지 않는다. */
    @Transactional
    public void approveLater(String paymentKey) {
        repository.lockByPaymentKey(paymentKey).ifPresent(transaction -> transaction.approveIfReady(now()));
    }

    @Transactional
    public PgTransaction cancel(String paymentKey, String cancelReason, Integer cancelAmount, boolean reject) {
        PgTransaction transaction = repository
                .lockByPaymentKey(paymentKey)
                .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYMENT));
        transaction.verifyCancelable(cancelAmount);
        if (reject) {
            throw new PgException(PgErrorCode.NOT_CANCELABLE_PAYMENT);
        }
        transaction.cancel(cancelReason, now());
        return transaction;
    }

    @Transactional(readOnly = true)
    public PgTransaction findByOrderId(String orderId) {
        return repository
                .findByProviderAndOrderId(PgProvider.TOSS, orderId)
                .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYMENT));
    }

    @Transactional(readOnly = true)
    public PgTransaction findByPaymentKey(String paymentKey) {
        return repository
                .findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYMENT));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
