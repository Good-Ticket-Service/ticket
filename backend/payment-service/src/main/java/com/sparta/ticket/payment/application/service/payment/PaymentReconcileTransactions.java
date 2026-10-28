package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.ReconcileClaim;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentReconcileTransactions {
    private final PaymentRepository payments;
    private final PaymentTransactions transactions;
    private final PaymentReconcileProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ReconcileRetryDelay retryDelay;

    @Transactional
    public Optional<ReconcileClaim> claimOne(Collection<Long> attemptedIds) {
        LocalDateTime now = LocalDateTime.now(clock);
        return payments.lockNextReconcile(now, attemptedIds).stream()
                .findFirst()
                .map(payment -> {
                    attemptedIds.add(payment.getId());
                    LocalDateTime until =
                            now.plusSeconds(properties.leaseSeconds()).truncatedTo(ChronoUnit.MICROS);
                    payment.claimReconciliation(until);
                    return new ReconcileClaim(
                            payment.getId(),
                            payment.getOrderId(),
                            payment.getPgProvider(),
                            payment.getPgTransactionId(),
                            payment.getReconcileCount(),
                            until);
                });
    }

    @Transactional
    public void apply(ReconcileClaim claim, PgInquiry inquiry) {
        Payment payment = payments.lockById(claim.paymentId()).orElse(null);
        if (payment == null) return;
        boolean confirmed = false;
        if (inquiry instanceof PgInquiry.Approved approved
                && approved.transactionId() != null
                && approved.transactionId().equals(payment.getPgTransactionId())
                && approved.amount() > 0
                && approved.approvedAt() != null) {
            // 점유가 바뀌어도 확인된 과금은 환불 의도로 인계한다. 체크포인트 진행은 아래 소유 검사와 별개다.
            transactions.complete(
                    payment.getId(),
                    new PgResult.Approved(approved.transactionId(), approved.amount(), approved.approvedAt()));
            confirmed = true;
        } else if (inquiry instanceof PgInquiry.NotFound) {
            confirmed = true;
        } else if (inquiry instanceof PgInquiry.Canceled canceled) {
            confirmed = canceled.transactionId() != null
                    && canceled.canceledAt() != null
                    && canceled.transactionId().equals(payment.getPgTransactionId());
        }
        if (!payment.needsReconciliation()
                || payment.getReconcileCount() != claim.checkpoint()
                || !Objects.equals(payment.getNextReconcileAt(), claim.claimedUntil())) return;
        LocalDateTime now = LocalDateTime.now(clock);
        if (confirmed) {
            payment.advanceReconciliation();
            afterCommit(() -> meters.counter("payment.reconcile.confirmed").increment());
        } else {
            int retries = payment.getReconcileRetryCount();
            payment.deferReconciliation(
                    now, now.plus(retryDelay.afterUnknown(retries == Integer.MAX_VALUE ? retries : retries + 1)));
            LocalDateTime since = payment.getReconcileUnknownSince();
            boolean warning = !now.isBefore(since.plusMinutes(properties.unknownWarningMinutes()));
            boolean critical = !now.isBefore(since.plusMinutes(properties.unknownCriticalMinutes()));
            afterCommit(() -> {
                meters.counter("payment.reconcile.unknown").increment();
                if (critical) {
                    meters.counter("payment.reconcile.long.unknown").increment();
                    meters.counter("payment.reconcile.unknown.critical").increment();
                    log.error("Reconcile 장기 조회 불명. 운영 확인 필요. paymentId={}", payment.getId());
                } else if (warning) {
                    meters.counter("payment.reconcile.unknown.warning").increment();
                    log.warn("Reconcile 조회 불명 지속. paymentId={}", payment.getId());
                }
            });
        }
    }

    @Transactional(readOnly = true)
    public double oldestDueAgeSeconds() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldest = payments.oldestDueReconcile(now);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis() / 1000.0);
    }

    /** 재조회 대기·점유 중에도 연속 불명 경과 시간을 관측한다. */
    @Transactional(readOnly = true)
    public double oldestUnknownAgeSeconds() {
        LocalDateTime oldest = payments.oldestUnknownReconcile();
        return oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toMillis() / 1000.0);
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
