package com.sparta.ticket.payment.application.service.observation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class CommittedObservation {
    private final MeterRegistry meters;
    private final Clock clock;

    public void counter(String name, String... tags) {
        afterCommit(() -> meters.counter(name, tags).increment());
    }

    public void duration(String name, LocalDateTime startedAt, String... tags) {
        afterCommit(() -> {
            Duration elapsed = Duration.between(startedAt, LocalDateTime.now(clock));
            meters.timer(name, tags).record(elapsed.isNegative() ? Duration.ZERO : elapsed);
        });
    }

    private void afterCommit(Runnable update) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    update.run();
                } catch (RuntimeException e) {
                    log.warn("커밋된 금융 결과의 계측 실패");
                }
            }
        });
    }
}
