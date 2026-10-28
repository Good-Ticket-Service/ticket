package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.RecoveryClaim;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgInquireRequest;
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

/** 순차 MVP 기준선. 미실행 대상을 미리 묶음 점유하지 않고 PG 호출 직전에 한 건씩 점유한다. */
@Slf4j
@Component
public class PaymentRecoveryJob {
    private final PaymentRecoveryTransactions transactions;
    private final PgClientRegistry clients;
    private final PaymentRecoveryProperties properties;
    private final MeterRegistry meters;
    private final AtomicReference<Double> oldestDueAge = new AtomicReference<>(0.0);

    public PaymentRecoveryJob(
            PaymentRecoveryTransactions transactions,
            PgClientRegistry clients,
            PaymentRecoveryProperties properties,
            MeterRegistry meters) {
        this.transactions = transactions;
        this.clients = clients;
        this.properties = properties;
        this.meters = meters;
        meters.gauge("payment.recovery.oldest.due.age.seconds", oldestDueAge, AtomicReference::get);
    }

    /** 실행 예산이 지나면 새 조회를 시작하지 않는다. 이미 시작한 HTTP 호출은 해당 타임아웃까지 걸릴 수 있다. */
    public void runOnce() {
        long started = System.nanoTime();
        long budget = TimeUnit.SECONDS.toNanos(properties.maxRunSeconds());
        List<Long> attemptedReady = new ArrayList<>(List.of(0L));
        for (int i = 0; i < properties.batchSize() && System.nanoTime() - started < budget; i++) {
            try {
                if (!transactions.expireOne(attemptedReady)) break;
            } catch (RuntimeException e) {
                failure("expire");
            }
        }
        List<Long> attemptedRecovery = new ArrayList<>(List.of(0L));
        for (int i = 0; i < properties.batchSize() && System.nanoTime() - started < budget; i++) {
            RecoveryClaim claim;
            try {
                Optional<RecoveryClaim> next = transactions.claimOne(attemptedRecovery);
                if (next.isEmpty()) break;
                claim = next.get();
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
                inquiry = new PgInquiry.Unknown("PG_INQUIRY_EXCEPTION");
            } finally {
                meters.timer("payment.recovery.inquiry.duration")
                        .record(System.nanoTime() - queryStarted, TimeUnit.NANOSECONDS);
            }
            try {
                transactions.apply(claim, inquiry);
            } catch (RuntimeException e) {
                failure("apply");
                log.error("Recovery 결과 저장 실패. 다음 점유에서 원거래를 조회한다. paymentId={}", claim.paymentId());
            }
        }
        try {
            oldestDueAge.set(transactions.oldestDueAgeSeconds());
        } catch (RuntimeException e) {
            failure("observe");
        }
        meters.timer("payment.recovery.run.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private void failure(String phase) {
        meters.counter("payment.recovery.errors", "phase", phase).increment();
        log.warn("Recovery 단계 실패. phase={}", phase);
    }
}
