package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
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

    /**
     * 묶음이 가득 찼으면 쉬지 않고 다음 묶음을 잇고, 덜 찼으면 휴지로 돌아간다(#384).
     * 고정 휴지는 "비어서 쉼"과 "가득 차고도 쉼"을 구분하지 못해 적체 때 회차 시간의 약 3/4이 휴지였다.
     * 한 tick의 묶음 수는 상한을 두어 스케줄러 스레드를 독점하지 않는다. 관측 조회는 tick마다 한 번이다.
     * 휴지는 비었을 때만 쓰이므로 처리량과 무관하고, 빈 상태에서 들어온 한 건의 최대 대기만 정한다.
     * 100ms는 빈 조회 초당 10회(측정 DB 시간 약 2ms/s)로 정상 유입의 발행 대기 중앙값을 0.5초에서 0.05초로 줄인다.
     */
    @Scheduled(fixedDelayString = "${payment.messaging.result-relay.fixed-delay-ms:100}")
    public void tick() {
        try {
            for (int round = 0; round < config.maxRoundsPerTick(); round++) {
                var result = publishRound();
                // Note: ACK가 하나도 없으면 broker 쪽 문제다. 다음 묶음도 실패할 것이므로 재시도 지연에 맡기고 휴지로 간다.
                if (result.claimed() < config.batchSize()
                        || result.completed() == 0
                        || Thread.currentThread().isInterrupted()) break;
            }
            pending = outbox.pending();
            oldest = outbox.oldestSeconds();
        } catch (RuntimeException exception) {
            meters.counter("payment.result.relay.failures").increment();
            log.warn("결제 결과 발행 회차 실패: {}", exception.getClass().getSimpleName());
        }
    }

    /** 한 묶음만 처리하고 완료 기록 수를 돌려준다. 시험과 운영 도구가 호출한다. */
    public int publishOnce() {
        return publishRound().completed();
    }

    private record Round(int claimed, int completed) {}

    /**
     * 한 묶음을 먼저 모두 송신하고 ACK를 모아 기다린 뒤, ACK 받은 건만 한 번에 완료 기록한다(#384).
     * 건별 동기 송신은 Producer 묶음을 막고 건별 commit을 만들어 초당 약 70건이 한도였다.
     * ACK 없는 건은 재전달 대상으로 남고, 완료 기록은 claim 때 받은 lease 소유권으로만 한다.
     */
    private Round publishRound() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결과 발행은 DB 트랜잭션 밖에서 실행해야 한다");
        }
        var deliveries = outbox.claim(config.batchSize(), config.lease());
        long deadline = System.nanoTime() + config.maxRun().toNanos();
        var inFlight = new ArrayList<InFlight>(deliveries.size());
        for (var delivery : deliveries) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                outbox.failed(delivery, "RUN_BUDGET", config.retryDelay());
                continue;
            }
            try {
                inFlight.add(new InFlight(delivery, kafka.send(TOPIC, delivery.key(), delivery.payload())));
            } catch (RuntimeException exception) {
                // Note: 송신 접수 자체가 거부된 경우(버퍼 가득 참, 직렬화 등)도 broker 보존 여부를 모르는 것으로 본다.
                outbox.failed(delivery, "DELIVERY_UNCERTAIN", retry(delivery.attempts()));
                meters.counter("payment.result.delivery.failures").increment();
            }
        }
        var acknowledged = new ArrayList<PaymentResultOutboxJdbc.Delivery>(inFlight.size());
        long waitUntil = System.nanoTime() + config.sendTimeout().toNanos();
        for (var pending : inFlight) {
            try {
                pending.future().get(Math.max(0, waitUntil - System.nanoTime()), TimeUnit.NANOSECONDS);
                meters.counter("payment.result.broker.acknowledged").increment();
                acknowledged.add(pending.delivery());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                outbox.failed(
                        pending.delivery(),
                        "INTERRUPTED",
                        retry(pending.delivery().attempts()));
            } catch (Exception exception) {
                // Note: payload/인증값과 broker 예외 원문을 기록하지 않는다. ACK가 유실돼도 같은 사건을 재전달한다.
                outbox.failed(
                        pending.delivery(),
                        "DELIVERY_UNCERTAIN",
                        retry(pending.delivery().attempts()));
                meters.counter("payment.result.delivery.failures").increment();
            }
        }
        int completed = acknowledged.isEmpty() ? 0 : outbox.published(acknowledged);
        meters.counter("payment.result.outbox.published").increment(completed);
        return new Round(deliveries.size(), completed);
    }

    private record InFlight(PaymentResultOutboxJdbc.Delivery delivery, CompletableFuture<?> future) {}

    private Duration retry(int attempts) {
        return Duration.ofMillis(Math.min(30000, config.retryDelay().toMillis() * (1L << Math.min(attempts - 1, 5))));
    }
}
