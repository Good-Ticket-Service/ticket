package com.sparta.ticket.performance.infrastructure.scheduling;

import com.sparta.ticket.performance.application.service.SeatSetupRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 결과 불명 FAILED만 제한된 횟수로 확인한다. */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "performance.seat-setup.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class SeatSetupRecoveryScheduler {
    private final SeatSetupRecoveryService service;

    @Value("${performance.seat-setup.recovery.batch-size:20}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${performance.seat-setup.recovery.fixed-delay-ms:5000}",
            initialDelayString = "${performance.seat-setup.recovery.fixed-delay-ms:5000}")
    public void recoverDue() {
        service.processDue(batchSize);
    }
}
