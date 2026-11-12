package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.FinancialReplayOutboxJdbc;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 저빈도 운영 재처리 발행기. source의 eventId는 유지하고 replayCommandId로 운영 결과를 연결한다. */
@Component
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(SettlementPostingKafkaProperties.class)
@ConditionalOnProperty(name = "payment.settlement.consumer-recovery.replay-enabled", havingValue = "true")
public class FinancialReplayRelay {
    private final FinancialReplayOutboxJdbc outbox;
    private final KafkaTemplate<String, String> kafka;
    private final SettlementPostingKafkaProperties config;
    private final MeterRegistry meters;

    @Scheduled(fixedDelayString = "${payment.settlement.consumer-recovery.relay-delay-ms:1000}")
    public void tick() {
        try {
            publishOnce();
        } catch (RuntimeException exception) {
            meters.counter("settlement.consumer.replay.relay.errors").increment();
            log.warn("금융 재처리 발행 실패: {}", exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${payment.settlement.consumer-recovery.redrive-delay-ms:60000}")
    public void recoverOverdue() {
        try {
            meters.counter("settlement.consumer.replay.overdue.redriven").increment(outbox.redriveOverdue());
        } catch (RuntimeException exception) {
            meters.counter("settlement.consumer.replay.relay.errors").increment();
            log.warn("장기 미반영 재처리 명령 복구 실패: {}", exception.getClass().getSimpleName());
        }
    }

    public void publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("재처리 발행은 TX 밖에서 실행해야 한다");
        var deliveries = outbox.claim(config.getLease());
        long deadline = System.nanoTime() + config.getMaxPublishRun().toNanos();
        for (var item : deliveries) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                outbox.failed(item, config.getRetryDelay());
                continue;
            }
            try {
                kafka.send(item.topic(), item.key(), item.payload())
                        .get(config.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                outbox.published(item);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                outbox.failed(item, config.getRetryDelay());
            } catch (Exception exception) {
                outbox.failed(item, config.getRetryDelay());
                meters.counter("settlement.consumer.replay.delivery.uncertain").increment();
            }
        }
    }
}
