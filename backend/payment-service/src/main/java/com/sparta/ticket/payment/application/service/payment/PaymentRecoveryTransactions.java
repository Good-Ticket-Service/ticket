package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.RecoveryClaim;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
public class PaymentRecoveryTransactions {
    private final PaymentRepository payments;
    private final PaymentTransactions transactions;
    private final PaymentRecoveryProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    /** Note: ID는 먼저 기록해 저장 실패 때도 같은 실행에서 동일 후보만 반복하지 않는다. */
    @Transactional
    public boolean expireOne(Collection<Long> attemptedIds) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Payment> candidate =
                payments.lockNextExpiredReady(now, attemptedIds).stream().findFirst();
        if (candidate.isEmpty()) return false;
        Payment payment = candidate.get();
        attemptedIds.add(payment.getId());
        if (payment.getStatus() == PaymentStatus.READY && payment.isExpired(LocalDateTime.now(clock))) {
            transactions.failForRecovery(payment, PaymentFailureCode.EXPIRED);
        }
        return true;
    }

    @Transactional
    public Optional<RecoveryClaim> claimOne() {
        return claimOne(new java.util.ArrayList<>(java.util.List.of(0L)));
    }

    @Transactional
    public Optional<RecoveryClaim> claimOne(Collection<Long> attemptedIds) {
        LocalDateTime now = LocalDateTime.now(clock);
        return payments.lockNextRecovery(now, attemptedIds).stream().findFirst().map(payment -> {
            attemptedIds.add(payment.getId());
            LocalDateTime until = now.plus(properties.interval()).truncatedTo(ChronoUnit.MICROS);
            payment.claimRecovery(until);
            return new RecoveryClaim(
                    payment.getId(),
                    payment.getOrderId(),
                    payment.getPgProvider(),
                    payment.getPgTransactionId(),
                    until);
        });
    }

    @Transactional
    public void apply(RecoveryClaim claim, PgInquiry inquiry) {
        Payment payment = payments.lockById(claim.paymentId()).orElse(null);
        if (payment == null) return;
        LocalDateTime now = LocalDateTime.now(clock);
        if (inquiry instanceof PgInquiry.Approved approved
                && Objects.equals(approved.transactionId(), payment.getPgTransactionId())
                && approved.transactionId() != null
                && approved.amount() > 0
                && approved.approvedAt() != null) {
            // Note: 점유가 바뀌었어도 확인된 과금은 버리지 않는다. FAILED면 기존 complete가 AUTO_VOID로 인계한다.
            transactions.complete(
                    payment.getId(),
                    new PgResult.Approved(approved.transactionId(), approved.amount(), approved.approvedAt()));
            return;
        }
        if (payment.getStatus() != PaymentStatus.IN_PROGRESS
                || !Objects.equals(payment.getNextAttemptAt(), claim.claimedUntil())) return;
        if (inquiry instanceof PgInquiry.Canceled canceled
                && canceled.transactionId() != null
                && canceled.canceledAt() != null
                && canceled.transactionId().equals(payment.getPgTransactionId())) {
            transactions.failForRecovery(payment, PaymentFailureCode.CANCELED_AT_PG);
        } else if (inquiry instanceof PgInquiry.NotFound) {
            if (payment.isNotFoundGraceOver(now, properties.grace())) {
                transactions.failForRecovery(payment, PaymentFailureCode.NOT_APPROVED);
            } else {
                payment.deferRecovery("PG_NOT_FOUND_WITHIN_GRACE", now.plus(properties.interval()));
            }
        } else if (payment.isResultDeadlineOver(now, properties.deadline())) {
            transactions.failForRecovery(payment, PaymentFailureCode.PG_UNRESOLVED);
            Long paymentId = payment.getId();
            // Note: 저장 실패로 롤백된 시도를 확정 건수로 집계하지 않는다.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    meters.counter("payment.unresolved").increment();
                    log.warn("결과 불명 시한 종료. 이후 Reconcile 확인이 필요하다. paymentId={}", paymentId);
                }
            });
        } else {
            payment.deferRecovery("PG_INQUIRY_UNKNOWN", now.plus(properties.interval()));
        }
    }

    @Transactional(readOnly = true)
    public double oldestDueAgeSeconds() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldest = payments.oldestDueRecovery(now);
        return oldest == null
                ? 0
                : Math.max(0, java.time.Duration.between(oldest, now).toMillis() / 1000.0);
    }
}
