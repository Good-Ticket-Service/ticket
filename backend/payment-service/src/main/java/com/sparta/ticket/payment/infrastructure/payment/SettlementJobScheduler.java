package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.service.payment.SettlementJobWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 정산 반영 워커(#304). 끄면 작업은 쌓이기만 하고 결제·환불 확정에는 영향이 없다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementJobScheduler {
    private final SettlementJobWorker worker;

    @Scheduled(fixedDelayString = "${payment.settlement.worker.fixed-delay-ms:1000}")
    public void work() {
        worker.runOnce();
    }
}
