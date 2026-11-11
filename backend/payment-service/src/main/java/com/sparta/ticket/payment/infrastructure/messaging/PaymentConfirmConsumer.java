package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** 성공한 DB 책임 인수·DLT 송신에만 ACK한다. 예외 Future의 자동 ACK 경로는 사용하지 않는다. */
@Component
@Slf4j
@ConditionalOnProperty(name = "payment.messaging.confirm.enabled", havingValue = "true")
public class PaymentConfirmConsumer {
    public static final String TOPIC = "payment.confirm.requested";
    public static final String ID = "payment-confirm";
    private final MessageApprovalService service;
    private final MessageApprovalProperties properties;
    private final PaymentConfirmOwnership ownership;
    private final KafkaListenerEndpointRegistry registry;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final Map<String, Work> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PaymentConfirmConsumer(
            MessageApprovalService service,
            MessageApprovalProperties properties,
            PaymentConfirmOwnership ownership,
            KafkaListenerEndpointRegistry registry,
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            MeterRegistry meters,
            Clock clock) {
        properties.validate();
        this.service = service;
        this.properties = properties;
        this.ownership = ownership;
        this.registry = registry;
        this.kafka = kafka;
        this.mapper = mapper;
        this.meters = meters;
        this.clock = clock;
        // Note: 첫 DLT 발생 전 0 기준선을 수집할 수 있게 counter를 미리 등록한다.
        meters.counter("payment.confirm.records", "outcome", "dlt_sent");
        meters.counter("payment.confirm.records", "outcome", "dlt_failed");
        workers = new ThreadPoolExecutor(
                properties.getWorkers(),
                properties.getWorkers(),
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getMaxInFlight()),
                runnable -> {
                    var thread = new Thread(runnable, "payment-confirm-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "payment-confirm-retry");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        meters.gauge("payment.confirm.pending", pending, Map::size);
        meters.gauge(
                "payment.confirm.paused",
                pending,
                map -> map.values().stream().filter(work -> work.paused).count());
        meters.gauge("payment.confirm.workers", workers, ThreadPoolExecutor::getActiveCount);
        meters.gauge("payment.confirm.queue", workers, pool -> pool.getQueue().size());
        meters.gauge(
                "payment.confirm.oldest.seconds",
                pending,
                map -> map.values().stream()
                        .mapToDouble(work -> (System.nanoTime() - work.enteredNanos) / 1_000_000_000.0)
                        .max()
                        .orElse(0));
        timer.scheduleWithFixedDelay(this::cleanRevoked, 200, 200, TimeUnit.MILLISECONDS);
    }

    @KafkaListener(
            id = ID,
            groupId = "payment-confirm-requested",
            topics = TOPIC,
            containerFactory = "paymentConfirmContainerFactory")
    public synchronized void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        // Note: 본문이 포함될 수 있는 예외를 framework 오류 로그로 전파하지 않는다.
        try {
            cleanRevoked();
            var partition = new TopicPartition(record.topic(), record.partition());
            var lease = ownership.lease(partition);
            if (closed || lease == null || !lease.active().get()) return;
            if (pending.size() >= properties.getMaxInFlight()) {
                pausePartition(partition);
                count("capacity_pause");
                return;
            }
            var work = new Work(record, acknowledgment, lease);
            if (pending.putIfAbsent(work.id, work) == null) {
                count("received");
                submit(work);
            }
        } catch (RuntimeException exception) {
            pausePartition(new TopicPartition(record.topic(), record.partition()));
            count("admission_failure");
        }
    }

    private void submit(Work work) {
        if (!active(work)) {
            remove(work);
            return;
        }
        try {
            workers.execute(() -> execute(work));
        } catch (RejectedExecutionException exception) {
            retry(work, true);
        }
    }

    private void execute(Work work) {
        if (!active(work)) {
            remove(work);
            return;
        }
        if (!work.running.compareAndSet(false, true)) return;
        try {
            if (work.command == null && !work.poison) {
                try {
                    work.command = PaymentConfirmMessage.parse(
                            mapper, work.record.key(), work.record.value(), properties.getMaxMessageBytes());
                } catch (PaymentConfirmMessage.InvalidMessage exception) {
                    work.poison = true;
                    count("invalid");
                }
            }
            if (work.poison) {
                sendDeadLetter(work);
                return;
            }
            var outcome = service.advance(work.command, () -> active(work));
            if (outcome == MessageApprovalService.Outcome.COMPLETE) complete(work);
            else if (outcome == MessageApprovalService.Outcome.RETRY) {
                count("local_wait");
                retry(work, false);
            } else remove(work);
        } catch (CannotGetJdbcConnectionException | TransientDataAccessException exception) {
            count("db_retry");
            retry(work, true);
        } catch (BusinessException exception) {
            if ("PAYMENT_NOT_FOUND".equals(exception.getErrorCode().code())) retry(work, true);
            else pause(work, "MEANING_CONFLICT");
        } catch (RuntimeException exception) {
            pause(work, "UNEXPECTED_FAILURE");
        } finally {
            work.running.set(false);
        }
    }

    private void sendDeadLetter(Work work) {
        try {
            String value = mapper.writeValueAsString(Map.of(
                    "schemaVersion",
                    1,
                    "sourceTopic",
                    work.record.topic(),
                    "sourcePartition",
                    work.record.partition(),
                    "sourceOffset",
                    work.record.offset(),
                    "reason",
                    "INVALID_CONFIRM_MESSAGE",
                    "firstFailedAt",
                    work.firstFailedAt.toString(),
                    "payloadHash",
                    hash(work.record.value())));
            String key = safeReservationKey(work.record.key());
            kafka.send(new ProducerRecord<>(TOPIC + ".DLT", work.record.partition(), key, value))
                    .whenComplete((sent, failure) -> {
                        if (failure == null) {
                            count("dlt_sent");
                            complete(work);
                        } else {
                            count("dlt_failed");
                            retry(work, true);
                        }
                    });
        } catch (Exception exception) {
            count("dlt_failed");
            retry(work, true);
        }
    }

    private void retry(Work work, boolean failure) {
        if (!active(work)) {
            remove(work);
            return;
        }
        if (failure && ++work.failures >= properties.getMaxInfrastructureRetries()) {
            pause(work, "RETRY_EXHAUSTED");
            return;
        }
        long base = Math.min(2000, properties.getRetryDelay().toMillis() * (1L << Math.min(work.failures, 3)));
        long delay = base + java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, base / 4));
        try {
            work.retry = timer.schedule(() -> submit(work), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (!closed) pause(work, "RETRY_SCHEDULER_UNAVAILABLE");
        }
    }

    private boolean active(Work work) {
        return !closed && !work.paused && work.lease.active().get();
    }

    private void complete(Work work) {
        try {
            if (active(work) && work.lease.acknowledgeIfOwned(work.acknowledgment::acknowledge)) count("completed");
            remove(work);
        } catch (RuntimeException exception) {
            pause(work, "ACK_FAILURE");
        }
    }

    private void pause(Work work, String reason) {
        work.paused = true;
        pausePartition(new TopicPartition(work.record.topic(), work.record.partition()));
        count("operation_pause");
        log.error(
                "승인 소비 운영 인수 필요. partition={}, offset={}, reason={}",
                work.record.partition(),
                work.record.offset(),
                reason);
    }

    private void pausePartition(TopicPartition partition) {
        var container = registry.getListenerContainer(ID);
        if (container != null) container.pausePartition(partition);
    }

    private void cleanRevoked() {
        pending.values().stream().filter(work -> !work.lease.active().get()).forEach(this::remove);
    }

    private void remove(Work work) {
        pending.remove(work.id, work);
        if (work.retry != null) work.retry.cancel(false);
    }

    private void count(String outcome) {
        try {
            meters.counter("payment.confirm.records", "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // Note: 관측 실패가 금융 처리나 offset 책임을 바꾸지 않는다.
        }
    }

    private static String hash(String value) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((value == null ? "<tombstone>" : value).getBytes(StandardCharsets.UTF_8)));
    }

    private static String safeReservationKey(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.parseLong(value) > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @PreDestroy
    public void close() {
        closed = true;
        ownership.close();
        timer.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        pending.clear();
    }

    private final class Work {
        final ConsumerRecord<String, String> record;
        final Acknowledgment acknowledgment;
        final PaymentConfirmOwnership.Lease lease;
        final String id;
        final OffsetDateTime firstFailedAt = OffsetDateTime.now(clock);
        final AtomicBoolean running = new AtomicBoolean();
        final long enteredNanos = System.nanoTime();
        volatile ScheduledFuture<?> retry;
        volatile boolean paused;
        boolean poison;
        int failures;
        MessageConfirmCommand command;

        Work(
                ConsumerRecord<String, String> record,
                Acknowledgment acknowledgment,
                PaymentConfirmOwnership.Lease lease) {
            this.record = record;
            this.acknowledgment = acknowledgment;
            this.lease = lease;
            id = record.partition() + ":" + record.offset() + ":" + lease.id();
        }
    }
}
