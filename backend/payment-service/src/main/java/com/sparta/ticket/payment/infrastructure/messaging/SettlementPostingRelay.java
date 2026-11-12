package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementPostingOutboxJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementPostingOutboxJdbc.Delivery;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 유한 batch를 비동기 송신하고 broker ACK를 확인한 작업만 발행 완료로 표시한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.posting.kafka.enabled", havingValue = "true")
public class SettlementPostingRelay {
    private final SettlementPostingOutboxJdbc outbox;
    private final KafkaTemplate<String, String> kafka;
    private final SettlementPostingKafkaProperties config;
    private final MeterRegistry meters;

    @Scheduled(fixedDelayString = "${payment.settlement.posting.kafka.relay-delay-ms:1000}")
    public void tick() {
        if (!config.isRelayEnabled()) return;
        try {
            publishOnce();
        } catch (RuntimeException exception) {
            meters.counter("settlement.posting.relay.errors").increment();
            log.warn("정산 사건 발행 회차 실패: {}", exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${payment.settlement.posting.kafka.redrive-delay-ms:60000}")
    public void recoverOverdue() {
        if (!config.isRelayEnabled()) return;
        try {
            meters.counter("settlement.posting.overdue.redriven")
                    .increment(outbox.redriveOverdue(config.getBatchSize(), java.time.Duration.ofMinutes(10)));
        } catch (RuntimeException exception) {
            meters.counter("settlement.posting.relay.errors").increment();
            log.warn("장기 미반영 정산 재전달 실패: {}", exception.getClass().getSimpleName());
        }
    }

    public int publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("정산 발행은 DB TX 밖에서 실행해야 한다");
        List<Delivery> claimed = outbox.claim(config.getBatchSize(), config.getLease());
        var submitted = new ArrayList<Pending>();
        var uncertain = new ArrayList<Delivery>();
        var deferred = new ArrayList<Delivery>();
        long submitUntil = System.nanoTime() + config.getMaxPublishRun().toNanos();
        for (Delivery delivery : claimed) {
            if (System.nanoTime() >= submitUntil || Thread.currentThread().isInterrupted()) {
                deferred.add(delivery);
                continue;
            }
            try {
                submitted.add(new Pending(
                        delivery,
                        kafka.send(config.getTopic(), Long.toString(delivery.paymentId()), delivery.payload())));
            } catch (RuntimeException exception) {
                uncertain.add(delivery);
            }
        }
        long ackUntil = System.nanoTime() + config.getSendTimeout().toNanos();
        var confirmed = new ArrayList<Delivery>();
        for (Pending item : submitted) {
            try {
                item.future().get(Math.max(0, ackUntil - System.nanoTime()), TimeUnit.NANOSECONDS);
                confirmed.add(item.delivery());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                uncertain.add(item.delivery());
            } catch (Exception exception) {
                // Note: 늦은 ACK가 와도 같은 eventId로 재전달한다. broker 예외·payload 원문은 남기지 않는다.
                uncertain.add(item.delivery());
            }
        }
        int published = outbox.published(confirmed);
        outbox.failed(uncertain, "DELIVERY_UNCERTAIN", config.getRetryDelay());
        outbox.failed(deferred, "RUN_BUDGET", config.getRetryDelay());
        meters.counter("settlement.posting.broker.acknowledged").increment(confirmed.size());
        meters.counter("settlement.posting.outbox.published").increment(published);
        meters.counter("settlement.posting.delivery.uncertain").increment(uncertain.size());
        return published;
    }

    private record Pending(Delivery delivery, CompletableFuture<SendResult<String, String>> future) {}
}
