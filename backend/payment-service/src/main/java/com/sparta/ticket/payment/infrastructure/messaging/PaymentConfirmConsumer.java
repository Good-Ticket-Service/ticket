package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalAttempt;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
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

/**
 * 성공한 DB 책임 인수(claim 커밋 또는 이미 처리됨)·DLT 송신에만 ACK한다. 예외 Future의 자동 ACK 경로는 사용하지 않는다.
 * #417: claim 커밋 직후 ACK해, PG 응답을 기다리는 레코드가 Partition의 다음 poll을 막지 않게 한다.
 */
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
    // Note: 상한을 넘겨 받은 레코드는 버리지 않고 여기서 기다린다. 버리면 asyncAcks offset 빈틈 때문에 재배정 전까지 파티션이 멈춘다(#396).
    private final Deque<Work> deferred = new ArrayDeque<>();
    private final Set<TopicPartition> capacityPaused = new HashSet<>();
    // Note: 운영 정지는 정지한 시점의 소유 lease에만 유효하다. 재배정 뒤의 새 소유에는 적용하지 않는다.
    private final Map<TopicPartition, java.util.UUID> operationPaused = new ConcurrentHashMap<>();
    private volatile boolean closed;
    /** claim 뒤 acknowledge했고 아직 PG·결과 저장이 끝나지 않은 작업 수(#417). pending에는 포함되지 않는다. */
    private final java.util.concurrent.atomic.AtomicInteger acknowledgedInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

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
                // Note: claim 뒤 acknowledge한 작업은 pending에서 빠진 채 worker를 쓴다(#417). 큐는 미완료 상한 + worker 수만큼 둔다.
                new ArrayBlockingQueue<>(properties.getMaxInFlight() + properties.getWorkers()),
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
        meters.gauge("payment.confirm.acknowledged.inflight", acknowledgedInFlight);
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
            var work = new Work(record, acknowledgment, lease);
            // Note: pending 등록과 상한 판정을 한 잠금 안에서 한다. 나눠 하면 완료 쪽 계산이 새 레코드를 활성으로 잘못 세어
            // 대기열을 넘기지 않고, 그 뒤 활성 0건이 되어 아무도 대기열을 깨우지 않는다.
            synchronized (deferred) {
                if (pending.putIfAbsent(work.id, work) != null) return;
                count("received");
                if (!deferred.isEmpty() || activeCount() > properties.getMaxInFlight()) {
                    deferred.addLast(work);
                    if (capacityPaused.add(partition)) {
                        pausePartition(partition);
                        count("capacity_pause");
                    }
                    return;
                }
            }
            submit(work);
        } catch (RuntimeException exception) {
            var partition = new TopicPartition(record.topic(), record.partition());
            synchronized (deferred) {
                var lease = ownership.lease(partition);
                // Note: 수신 실패도 운영 인수 대상이다. 상한 해소로 다시 켜지지 않게 운영 정지로 기록한다.
                if (lease != null) operationPaused.put(partition, lease.id());
                pausePartition(partition);
            }
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
            // Note: worker 큐가 찬 것은 처리 자원이 바쁜 것이지 인프라 실패가 아니다. 실패로 세면 재시도 소진으로 Partition이 정지된다.
            count("worker_queue_full");
            retry(work, false);
        }
    }

    private void execute(Work work) {
        if (!active(work)) {
            remove(work);
            return;
        }
        if (!work.running.compareAndSet(false, true)) return;
        try {
            if (work.attempt == null && !work.poison) {
                try {
                    work.attempt = new MessageApprovalAttempt(PaymentConfirmMessage.parse(
                            mapper, work.record.key(), work.record.value(), properties.getMaxMessageBytes()));
                } catch (PaymentConfirmMessage.InvalidMessage exception) {
                    work.poison = true;
                    count("invalid");
                }
            }
            if (work.poison) {
                sendDeadLetter(work);
                return;
            }
            Runnable accepted = properties.isAcknowledgeOnClaim() ? () -> acknowledgeOnClaim(work) : () -> {};
            var outcome = service.advance(work.attempt, () -> active(work), accepted);
            // Note: claim 직후 acknowledge한 작업은 이미 pending에서 빠졌다. 결과는 DB·Recovery가 책임진다.
            if (work.acknowledged) {
                count("finished_after_ack");
                return;
            }
            if (outcome == MessageApprovalService.Outcome.COMPLETE) complete(work);
            else if (outcome == MessageApprovalService.Outcome.RETRY) {
                count("local_wait");
                retry(work, false);
            } else remove(work);
        } catch (CannotGetJdbcConnectionException | TransientDataAccessException exception) {
            if (failedAfterAcknowledge(work, "db") || droppedWithoutOwnership(work)) return;
            count("db_retry");
            retry(work, true);
        } catch (BusinessException exception) {
            if (failedAfterAcknowledge(work, "business") || droppedWithoutOwnership(work)) return;
            if ("PAYMENT_NOT_FOUND".equals(exception.getErrorCode().code())) retry(work, true);
            else pause(work, "MEANING_CONFLICT");
        } catch (RuntimeException exception) {
            if (failedAfterAcknowledge(work, "unexpected") || droppedWithoutOwnership(work)) return;
            pause(work, "UNEXPECTED_FAILURE");
        } finally {
            if (work.acknowledged && work.inFlightCounted.compareAndSet(true, false))
                acknowledgedInFlight.decrementAndGet();
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

    /**
     * claim 커밋 직후 acknowledge한다(#417). 이 레코드는 더 이상 Partition 진행을 붙잡지 않는다.
     * acknowledge에 실패하면 표시하지 않고 계속 진행한다. 처리가 끝난 뒤 complete가 다시 시도한다.
     */
    private void acknowledgeOnClaim(Work work) {
        try {
            if (!active(work) || !work.lease.acknowledgeIfOwned(work.acknowledgment::acknowledge)) return;
        } catch (RuntimeException exception) {
            count("claim_ack_failure");
            return;
        }
        work.acknowledged = true;
        if (work.inFlightCounted.compareAndSet(false, true)) acknowledgedInFlight.incrementAndGet();
        count("completed");
        count("claim_acknowledged");
        // Note: acknowledge가 끝난 뒤의 정리 실패는 acknowledge 실패로 세지 않는다. 대기열 넘기기는 작업마다 따로 처리한다.
        remove(work);
    }

    /** acknowledge한 뒤의 실패는 재시도·정지하지 않는다. 결제는 IN_PROGRESS이고 Recovery가 PG를 조회해 끝낸다. */
    private boolean failedAfterAcknowledge(Work work, String kind) {
        if (!work.acknowledged) return false;
        count("post_ack_failure");
        log.warn(
                "acknowledge 뒤 승인 처리 실패. Recovery가 결과를 정한다. partition={}, offset={}, kind={}",
                work.record.partition(),
                work.record.offset(),
                kind);
        return true;
    }

    private void complete(Work work) {
        if (work.acknowledged) return;
        try {
            if (active(work) && work.lease.acknowledgeIfOwned(work.acknowledgment::acknowledge)) count("completed");
            remove(work);
        } catch (RuntimeException exception) {
            pause(work, "ACK_FAILURE");
        }
    }

    /**
     * 소유권을 잃은 작업의 실패는 Partition을 정지시키지 않는다(#417 리뷰). claim 뒤에는 소유권과 무관하게 진행하므로,
     * 그 사이 같은 Partition을 새 lease로 다시 받았을 수 있다. 새 소유자가 같은 레코드를 다시 받아 처리한다.
     */
    private boolean droppedWithoutOwnership(Work work) {
        if (active(work)) return false;
        count("dropped_without_ownership");
        remove(work);
        return true;
    }

    private void pause(Work work, String reason) {
        if (droppedWithoutOwnership(work)) return;
        var partition = new TopicPartition(work.record.topic(), work.record.partition());
        // Note: 다시 켜기와 같은 잠금 안에서 정지를 기록해야 상한 해소가 운영 정지를 덮어쓰지 않는다.
        synchronized (deferred) {
            work.paused = true;
            operationPaused.put(partition, work.lease.id());
            pausePartition(partition);
        }
        count("operation_pause");
        log.error(
                "승인 소비 운영 인수 필요. partition={}, offset={}, reason={}",
                work.record.partition(),
                work.record.offset(),
                reason);
        // Note: 정지된 작업은 상한 자리를 차지하지 않는다. 다른 파티션의 대기 레코드를 넘긴다.
        admitDeferred();
    }

    private void pausePartition(TopicPartition partition) {
        var container = registry.getListenerContainer(ID);
        if (container != null) container.pausePartition(partition);
    }

    private void cleanRevoked() {
        pending.values().stream().filter(work -> !work.lease.active().get()).forEach(this::remove);
    }

    private void remove(Work work) {
        boolean removed = pending.remove(work.id, work);
        if (work.retry != null) work.retry.cancel(false);
        if (removed) admitDeferred();
    }

    /** 자리가 나면 기다리던 레코드를 받은 순서대로 넘기고, 다 넘기면 상한 때문에 멈춘 파티션만 다시 켠다. */
    private void admitDeferred() {
        var admitted = new java.util.ArrayList<Work>();
        synchronized (deferred) {
            deferred.removeIf(waiting -> pending.get(waiting.id) != waiting);
            while (!deferred.isEmpty() && activeCount() < properties.getMaxInFlight()) {
                var next = deferred.pollFirst();
                // Note: 소유를 잃었거나 닫힌 작업은 여기서 바로 지운다. submit→remove→admitDeferred 재귀를 만들지 않는다.
                if (active(next)) admitted.add(next);
                else pending.remove(next.id, next);
            }
            if (deferred.isEmpty() && !capacityPaused.isEmpty()) {
                // Note: 운영 정지(pause)된 파티션은 사람이 인수하기 전까지 다시 켜지 않는다. 정지 기록과 같은 잠금 안에서 확인·실행한다.
                capacityPaused.stream()
                        .filter(partition -> !operationPausedNow(partition))
                        .forEach(this::resumePartition);
                capacityPaused.clear();
            }
        }
        for (var next : admitted) {
            try {
                submit(next);
            } catch (RuntimeException exception) {
                // Note: 넘기기에 실패한 작업을 대기열 앞에 되돌린다. 버리면 acknowledge되지 않은 offset이 남아 Partition이 멈춘다.
                count("admit_failure");
                synchronized (deferred) {
                    deferred.addFirst(next);
                }
            }
        }
    }

    /** 상한에 세는 진행 중 작업 수. 대기열과 운영 정지된 작업은 뺀다. 호출자는 deferred 잠금을 잡는다. */
    private int activeCount() {
        int parked = (int) pending.values().stream().filter(work -> work.paused).count();
        return pending.size() - deferred.size() - parked;
    }

    private boolean operationPausedNow(TopicPartition partition) {
        var pausedLease = operationPaused.get(partition);
        var lease = ownership.lease(partition);
        return pausedLease != null && lease != null && pausedLease.equals(lease.id());
    }

    private void resumePartition(TopicPartition partition) {
        if (closed) return;
        var container = registry.getListenerContainer(ID);
        if (container != null) container.resumePartition(partition);
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
        synchronized (deferred) {
            deferred.clear();
            capacityPaused.clear();
        }
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
        volatile boolean acknowledged;
        final AtomicBoolean inFlightCounted = new AtomicBoolean();
        boolean poison;
        int failures;
        MessageApprovalAttempt attempt;

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
