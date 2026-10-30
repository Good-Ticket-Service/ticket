package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.service.payment.PaymentSettlementBackfillJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 정산 보충 잡. 운영 기본은 꺼 두고, 포착을 켤 때 함께 켠다(Task 20-4). */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.backfill.enabled", havingValue = "true")
public class PaymentSettlementBackfillScheduler {
    private final PaymentSettlementBackfillJob job;

    @Scheduled(fixedDelayString = "${payment.settlement.backfill.fixed-delay-ms:600000}")
    public void backfill() {
        job.runOnce();
    }
}
