package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalAttempt;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

/**
 * #396 H3. 미완료 상한에 닿아 파티션을 멈춘 뒤에도 이미 받은 레코드를 처리하고, 자리가 나면 파티션을 다시 켠다.
 * 받은 레코드를 버리면 asyncAcks의 offset 빈틈 때문에 재배정 전까지 파티션 전체가 멈춘다.
 */
class PaymentConfirmConsumerCapacityTest {
    private static final TopicPartition PARTITION = new TopicPartition(PaymentConfirmConsumer.TOPIC, 0);
    private static final TopicPartition OTHER = new TopicPartition(PaymentConfirmConsumer.TOPIC, 1);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MessageApprovalService service = mock(MessageApprovalService.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();
    private PaymentConfirmConsumer consumer;

    private PaymentConfirmConsumer consumer(int maxInFlight) {
        var properties = new MessageApprovalProperties();
        properties.setEnabled(true);
        properties.setConcurrency(1);
        properties.setMaxPollRecords(1);
        properties.setWorkers(4);
        properties.setPerProvider(2);
        properties.setMaxInFlight(maxInFlight);
        properties.setRetryDelay(Duration.ofMillis(10));
        var ownership = new PaymentConfirmOwnership();
        ownership.onPartitionsAssigned(null, List.of(PARTITION, OTHER));
        when(registry.getListenerContainer(PaymentConfirmConsumer.ID)).thenReturn(container);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        consumer = new PaymentConfirmConsumer(
                service, properties, ownership, registry, kafka, mapper, meters, Clock.systemDefaultZone());
        return consumer;
    }

    @AfterEach
    void close() {
        gates.values().forEach(CountDownLatch::countDown);
        if (consumer != null) consumer.close();
    }

    private ConsumerRecord<String, String> record(long offset) throws Exception {
        return record(PARTITION, offset);
    }

    private ConsumerRecord<String, String> record(TopicPartition partition, long offset) throws Exception {
        String order = "order-" + offset;
        String value = mapper.writeValueAsString(Map.of(
                "eventId",
                UUID.randomUUID().toString(),
                "schemaVersion",
                1,
                "occurredAt",
                "2026-11-11T12:00:00+09:00",
                "orderId",
                order,
                "reservationId",
                12,
                "amount",
                10000,
                "confirmBefore",
                "2026-11-11T12:05:00+09:00",
                "pgAuthToken",
                "token-" + offset));
        return new ConsumerRecord<>(partition.topic(), partition.partition(), offset, "12", value);
    }

    /** 처리 순서(=받은 순서)별 문을 열 때까지 PG 대기처럼 막혀 있다가 완료한다. */
    private void blockUntilReleased() throws Exception {
        when(service.advance(any(MessageApprovalAttempt.class), any(BooleanSupplier.class)))
                .thenAnswer(call -> {
                    long order = calls.getAndIncrement();
                    gates.computeIfAbsent("order-" + order, key -> new CountDownLatch(1))
                            .await(10, TimeUnit.SECONDS);
                    return MessageApprovalService.Outcome.COMPLETE;
                });
    }

    private void release(long offset) {
        gates.computeIfAbsent("order-" + offset, key -> new CountDownLatch(1)).countDown();
    }

    private double count(String outcome) {
        var counter =
                meters.find("payment.confirm.records").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void recordReceivedOverCapacityIsKeptAndPartitionResumesWhenSlotFrees() throws Exception {
        blockUntilReleased();
        var subject = consumer(1);
        var first = mock(Acknowledgment.class);
        var second = mock(Acknowledgment.class);

        subject.receive(record(0), first);
        subject.receive(record(1), second);

        verify(container).pausePartition(PARTITION);
        assertThat(count("capacity_pause")).isEqualTo(1);
        verify(container, never()).resumePartition(PARTITION);

        release(0);
        verify(first, timeout(5000)).acknowledge();
        verify(container, timeout(5000)).resumePartition(PARTITION);

        release(1);
        verify(second, timeout(5000)).acknowledge();
        await().atMost(Duration.ofSeconds(5)).until(() -> count("completed") == 2);
    }

    @Test
    void partitionStaysPausedWhileDeferredWorkRemains() throws Exception {
        blockUntilReleased();
        var subject = consumer(1);
        var acks = List.of(mock(Acknowledgment.class), mock(Acknowledgment.class), mock(Acknowledgment.class));

        for (int offset = 0; offset < 3; offset++) subject.receive(record(offset), acks.get(offset));

        release(0);
        verify(acks.get(0), timeout(5000)).acknowledge();
        verify(container, never()).resumePartition(PARTITION);

        release(1);
        verify(acks.get(1), timeout(5000)).acknowledge();
        verify(container, timeout(5000)).resumePartition(PARTITION);

        release(2);
        verify(acks.get(2), timeout(5000)).acknowledge();
    }

    @Test
    void operationPauseIsNotResumedByCapacityRecovery() throws Exception {
        when(service.advance(any(MessageApprovalAttempt.class), any(BooleanSupplier.class)))
                .thenThrow(new IllegalStateException("unexpected"));
        var subject = consumer(1);
        var first = mock(Acknowledgment.class);
        var second = mock(Acknowledgment.class);

        subject.receive(record(0), first);
        await().atMost(Duration.ofSeconds(5)).until(() -> count("operation_pause") == 1);
        subject.receive(record(1), second);

        verify(container, timeout(1000).atLeastOnce()).pausePartition(PARTITION);
        Thread.sleep(300);
        verify(container, never()).resumePartition(PARTITION);
        verify(first, never()).acknowledge();
    }

    @Test
    void operationPausedWorkDoesNotHoldCapacityForOtherPartitions() throws Exception {
        when(service.advance(any(MessageApprovalAttempt.class), any(BooleanSupplier.class)))
                .thenThrow(new IllegalStateException("unexpected"))
                .thenReturn(MessageApprovalService.Outcome.COMPLETE);
        var subject = consumer(1);
        var stuck = mock(Acknowledgment.class);
        var other = mock(Acknowledgment.class);

        subject.receive(record(PARTITION, 0), stuck);
        await().atMost(Duration.ofSeconds(5)).until(() -> count("operation_pause") == 1);
        subject.receive(record(OTHER, 0), other);

        verify(other, timeout(5000)).acknowledge();
        verify(container, never()).pausePartition(OTHER);
        verify(stuck, never()).acknowledge();
    }
}
