package com.sparta.ticket.fakepg.application.service.kakao;

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
 * 카카오 거래의 상태를 바꾸는 트랜잭션 단위. 토스와 같이 거래 행을 잠근 뒤 판단하고, 거절·만료는 상태를 커밋한 뒤 결과만 돌려준다.
 * 토스 거래의 키(paymentKey)로 카카오 API를 부르면 없는 거래로 본다.
 */
@Component
@RequiredArgsConstructor
public class KakaoPaymentTransactions {

    private final PgTransactionRepository repository;
    private final Clock clock;

    @Transactional
    public PgTransaction ready(String orderId, String itemName, int amount, String partnerUserId, int validMinutes) {
        LocalDateTime now = now();
        return repository
                .lockByProviderAndOrderId(PgProvider.KAKAO, orderId)
                .map(existing -> {
                    existing.reprepareKakao(amount, partnerUserId, now, validMinutes);
                    return existing;
                })
                .orElseGet(() -> repository.saveAndFlush(
                        PgTransaction.prepareKakao(orderId, itemName, amount, partnerUserId, now, validMinutes)));
    }

    /** 사용자 승인 시뮬레이션. 인증 유효 시간이 지났으면 만료로 커밋하고 결과만 돌려준다. */
    @Transactional
    public ConfirmResult issuePgToken(String tid) {
        LocalDateTime now = now();
        PgTransaction transaction = lock(tid, PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
        if (transaction.isAuthExpired(now)) {
            transaction.expire(now);
            return new ConfirmResult(transaction, ConfirmOutcome.EXPIRED);
        }
        transaction.issuePgToken(now);
        return new ConfirmResult(transaction, ConfirmOutcome.APPROVED);
    }

    @Transactional
    public ConfirmResult approve(
            String tid, String orderId, String partnerUserId, String pgToken, ConfirmDecision decision) {
        LocalDateTime now = now();
        PgTransaction transaction = lock(tid, PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
        transaction.verifyKakaoApprovable(orderId, partnerUserId, pgToken);
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
    public void approveLater(String tid) {
        repository
                .lockByPaymentKey(tid)
                .filter(transaction -> transaction.isProvidedBy(PgProvider.KAKAO))
                .ifPresent(transaction -> transaction.approveIfReady(now()));
    }

    @Transactional
    public PgTransaction cancel(String tid, int cancelAmount, boolean reject) {
        PgTransaction transaction = lock(tid, PgErrorCode.NOT_FOUND_PAYMENT);
        transaction.verifyCancelable(cancelAmount);
        if (reject) {
            throw new PgException(PgErrorCode.NOT_CANCELABLE_PAYMENT);
        }
        transaction.cancel(null, now());
        return transaction;
    }

    @Transactional(readOnly = true)
    public PgTransaction findByTid(String tid) {
        return repository
                .findByPaymentKey(tid)
                .filter(transaction -> transaction.isProvidedBy(PgProvider.KAKAO))
                .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYMENT));
    }

    private PgTransaction lock(String tid, PgErrorCode notFound) {
        return repository
                .lockByPaymentKey(tid)
                .filter(transaction -> transaction.isProvidedBy(PgProvider.KAKAO))
                .orElseThrow(() -> new PgException(notFound));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
