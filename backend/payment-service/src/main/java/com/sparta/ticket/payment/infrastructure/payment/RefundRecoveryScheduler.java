package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.service.refund.RefundRecoveryJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.recovery.refund.enabled", havingValue = "true", matchIfMissing = true)
public class RefundRecoveryScheduler {
    private final RefundRecoveryJob job;

    @Scheduled(fixedDelayString = "${payment.recovery.refund.fixed-delay-ms:1000}")
    public void recover() {
        job.runOnce();
    }
}
