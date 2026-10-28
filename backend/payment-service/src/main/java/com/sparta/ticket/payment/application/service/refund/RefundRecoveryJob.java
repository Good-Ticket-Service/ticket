package com.sparta.ticket.payment.application.service.refund;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RefundRecoveryJob {
    private final RefundTransactions transactions;
    private final RefundService service;
    private final RefundRecoveryProperties properties;
    private final MeterRegistry meters;
    private final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicReference<Double> oldestDue = new AtomicReference<>(0.0);

    public RefundRecoveryJob(
            RefundTransactions transactions,
            RefundService service,
            RefundRecoveryProperties properties,
            MeterRegistry meters) {
        this.transactions = transactions;
        this.service = service;
        this.properties = properties;
        this.meters = meters;
        meters.gauge("payment.refund.oldest.due.age.seconds", oldestDue, AtomicReference::get);
    }

    public void runOnce() {
        long started = System.nanoTime();
        boolean followUpFirst = runs.getAndIncrement() % 2 != 0;
        List<Long> attempted = new ArrayList<>(List.of(0L));
        for (int i = 0;
                i < properties.batchSize()
                        && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(properties.maxRunSeconds());
                i++) {
            int previous = attempted.size();
            try {
                boolean followUp = (i == 0 && followUpFirst) || i % 4 == 3;
                var next = transactions.claimOne(attempted, followUp);
                if (next.isEmpty() && attempted.size() == previous) next = transactions.claimOne(attempted, !followUp);
                if (next.isEmpty()) {
                    if (attempted.size() > previous) continue;
                    break;
                }
                service.execute(next.get());
            } catch (RuntimeException e) {
                meters.counter("payment.refund.errors", "phase", "recovery").increment();
                log.warn("환불 Recovery 실패. 미완료 기록으로 재개한다");
                if (attempted.size() == previous) break;
            }
        }
        try {
            oldestDue.set(transactions.oldestDueAgeSeconds());
        } catch (RuntimeException e) {
            meters.counter("payment.refund.errors", "phase", "observe").increment();
        }
        meters.timer("payment.recovery.refund.run.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
}
