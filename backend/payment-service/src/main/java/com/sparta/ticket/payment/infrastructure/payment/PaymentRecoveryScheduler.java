package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.service.payment.PaymentRecoveryJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryScheduler {
    private final PaymentRecoveryJob job;

    @Scheduled(fixedDelayString = "${payment.recovery.fixed-delay-ms:1000}")
    public void recover() {
        job.runOnce();
    }
}
