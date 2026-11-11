package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** claim과 완료 기록만 DB TX다. broker 대기 중에는 연결/잠금을 점유하지 않는다. */
@Slf4j
@Component
@EnableConfigurationProperties(PaymentResultRelayProperties.class)
@ConditionalOnProperty(name = "payment.messaging.result-relay.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentResultRelay {
    public static final String TOPIC = "payment.result";
    private final PaymentResultOutboxJdbc outbox;
    private final KafkaTemplate<String, String> kafka;
    private final PaymentResultRelayProperties config;
    private final MeterRegistry meters;
    private volatile long pending;
    private volatile double oldest;

    public PaymentResultRelay(
            PaymentResultOutboxJdbc outbox,
            KafkaTemplate<String, String> kafka,
            PaymentResultRelayProperties config,
            MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.config = config;
        this.meters = meters;
        Gauge.builder("payment.result.outbox.pending", this, relay -> relay.pending)
                .register(meters);
        Gauge.builder("payment.result.outbox.oldest.seconds", this, relay -> relay.oldest)
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payment.messaging.result-relay.fixed-delay-ms:1000}")
    public void tick() {
        try {
            publishOnce();
            pending = outbox.pending();
            oldest = outbox.oldestSeconds();
        } catch (RuntimeException exception) {
            meters.counter("payment.result.relay.failures").increment();
            log.warn("결제 결과 발행 회차 실패: {}", exception.getClass().getSimpleName());
        }
    }

    public int publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결과 발행은 DB 트랜잭션 밖에서 실행해야 한다");
        }
        var deliveries = outbox.claim(config.batchSize(), config.lease());
        long deadline = System.nanoTime() + config.maxRun().toNanos();
        int completed = 0;
        boolean stopped = false;
        for (var delivery : deliveries) {
            if (stopped
                    || System.nanoTime() >= deadline
                    || Thread.currentThread().isInterrupted()) {
                outbox.failed(delivery, "RUN_BUDGET", config.retryDelay());
                continue;
            }
            try {
                kafka.send(TOPIC, delivery.key(), delivery.payload())
                        .get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                meters.counter("payment.result.broker.acknowledged").increment();
                if (outbox.published(delivery)) {
                    completed++;
                    meters.counter("payment.result.outbox.published").increment();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                outbox.failed(delivery, "INTERRUPTED", retry(delivery.attempts()));
                stopped = true;
            } catch (Exception exception) {
                // Note: payload/인증값과 broker 예외 원문을 기록하지 않는다. ACK가 유실돼도 같은 사건을 재전달한다.
                outbox.failed(delivery, "DELIVERY_UNCERTAIN", retry(delivery.attempts()));
                meters.counter("payment.result.delivery.failures").increment();
                stopped = true;
            }
        }
        return completed;
    }

    private Duration retry(int attempts) {
        return Duration.ofMillis(Math.min(30000, config.retryDelay().toMillis() * (1L << Math.min(attempts - 1, 5))));
    }
}
