package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.service.payment.PaymentReconcileJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconcileScheduler {
    private final PaymentReconcileJob job;

    @Scheduled(fixedDelayString = "${payment.reconcile.fixed-delay-ms:1000}")
    public void reconcile() {
        job.runOnce();
    }
}
