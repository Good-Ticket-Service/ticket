package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.ReconcileClaim;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgInquireRequest;
import com.sparta.ticket.payment.application.service.observation.JobObservation;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PaymentReconcileJob {
    private final PaymentReconcileTransactions transactions;
    private final PgClientRegistry clients;
    private final PaymentReconcileProperties properties;
    private final MeterRegistry meters;
    private final JobObservation observation;
    private final AtomicReference<Double> oldestDueAge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> oldestUnknownAge = new AtomicReference<>(0.0);

    public PaymentReconcileJob(
            PaymentReconcileTransactions transactions,
            PgClientRegistry clients,
            PaymentReconcileProperties properties,
            MeterRegistry meters) {
        this.transactions = transactions;
        this.clients = clients;
        this.properties = properties;
        this.meters = meters;
        this.observation = new JobObservation(meters, "reconcile");
        meters.gauge("payment.reconcile.oldest.due.age.seconds", oldestDueAge, AtomicReference::get);
        meters.gauge("payment.reconcile.oldest.unknown.age.seconds", oldestUnknownAge, AtomicReference::get);
    }

    public void runOnce() {
        observation.run(this::executeOnce);
    }

    private void executeOnce() {
        long started = System.nanoTime();
        long budget = TimeUnit.SECONDS.toNanos(properties.maxRunSeconds());
        List<Long> attemptedIds = new ArrayList<>(List.of(0L));
        for (int i = 0; i < properties.batchSize() && System.nanoTime() - started < budget; i++) {
            ReconcileClaim claim;
            try {
                Optional<ReconcileClaim> candidate = transactions.claimOne(attemptedIds);
                if (candidate.isEmpty()) break;
                claim = candidate.get();
                observation.claimed();
            } catch (RuntimeException e) {
                failure("claim");
                break;
            }
            PgInquiry inquiry;
            long queryStarted = System.nanoTime();
            try {
                inquiry = clients.get(claim.provider())
                        .inquire(new PgInquireRequest(claim.orderId(), claim.transactionId()));
            } catch (RuntimeException e) {
                inquiry = new PgInquiry.Unknown("PG_RECONCILE_EXCEPTION");
            } finally {
                meters.timer("payment.reconcile.inquiry.duration")
                        .record(System.nanoTime() - queryStarted, TimeUnit.NANOSECONDS);
            }
            try {
                transactions.apply(claim, inquiry);
            } catch (RuntimeException e) {
                failure("apply");
                log.error("Reconcile 결과 저장 실패. 점유 만료 뒤 재조회한다. paymentId={}", claim.paymentId());
            }
        }
        try {
            oldestDueAge.set(transactions.oldestDueAgeSeconds());
            oldestUnknownAge.set(transactions.oldestUnknownAgeSeconds());
        } catch (RuntimeException e) {
            failure("observe");
        }
        meters.timer("payment.reconcile.run.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private void failure(String phase) {
        observation.failed();
        meters.counter("payment.reconcile.errors", "phase", phase).increment();
        log.warn("Reconcile 단계 실패. phase={}", phase);
    }
}
