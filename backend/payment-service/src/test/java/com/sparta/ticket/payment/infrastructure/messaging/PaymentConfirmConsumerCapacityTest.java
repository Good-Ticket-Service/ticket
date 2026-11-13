package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private final PaymentConfirmOwnership ownership = new PaymentConfirmOwnership();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private PaymentConfirmConsumer consumer(int maxInFlight) {
        var properties = new MessageApprovalProperties();
        properties.setEnabled(true);
        properties.setConcurrency(1);
        properties.setMaxPollRecords(1);
        properties.setWorkers(4);
        properties.setPerProvider(2);
        properties.setMaxInFlight(maxInFlight);
        properties.setRetryDelay(Duration.ofMillis(10));
        ownership.onPartitionsAssigned(null, List.of(PARTITION, OTHER));
        when(registry.getListenerContainer(PaymentConfirmConsumer.ID)).thenReturn(container);
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
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
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
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
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
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
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

    @Test
    void acknowledgesRightAfterClaimWithoutWaitingForPgAndNeverTwice() throws Exception {
        var claimed = new CountDownLatch(1);
        var pgDone = new CountDownLatch(1);
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
                .thenAnswer(call -> {
                    Runnable accepted = call.getArgument(2);
                    accepted.run();
                    claimed.countDown();
                    pgDone.await(10, TimeUnit.SECONDS);
                    return MessageApprovalService.Outcome.COMPLETE;
                });
        var subject = consumer(4);
        var ack = mock(Acknowledgment.class);

        subject.receive(record(0), ack);

        assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
        verify(ack, timeout(2000)).acknowledge();
        assertThat(count("claim_acknowledged")).isEqualTo(1);
        pgDone.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> count("completed") == 1);
        Thread.sleep(200);
        verify(ack, org.mockito.Mockito.times(1)).acknowledge();
        assertThat(count("completed")).isEqualTo(1);
    }

    @Test
    void failureAfterAcknowledgeDoesNotPauseOrRetry() throws Exception {
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
                .thenAnswer(call -> {
                    Runnable accepted = call.getArgument(2);
                    accepted.run();
                    throw new IllegalStateException("result save failed");
                });
        var subject = consumer(4);
        var ack = mock(Acknowledgment.class);

        subject.receive(record(0), ack);

        await().atMost(Duration.ofSeconds(5)).until(() -> count("post_ack_failure") == 1);
        verify(ack).acknowledge();
        verify(container, never()).pausePartition(PARTITION);
        assertThat(count("operation_pause")).isZero();
        verify(service, org.mockito.Mockito.times(1))
                .advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean());
    }

    @Test
    void acknowledgedClaimFreesCapacityForTheNextRecord() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var hold = new CountDownLatch(1);
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
                .thenAnswer(call -> {
                    Runnable accepted = call.getArgument(2);
                    accepted.run();
                    if (calls.incrementAndGet() == 1) hold.await(10, TimeUnit.SECONDS);
                    return MessageApprovalService.Outcome.COMPLETE;
                });
        var subject = consumer(1);
        var first = mock(Acknowledgment.class);
        var second = mock(Acknowledgment.class);

        subject.receive(record(0), first);
        verify(first, timeout(2000)).acknowledge();
        subject.receive(record(1), second);

        // Note: 첫 레코드는 PG 대기 중이지만 claim 뒤 acknowledge로 상한 자리를 비웠다. 두 번째는 대기 없이 처리된다.
        verify(second, timeout(2000)).acknowledge();
        verify(container, never()).pausePartition(PARTITION);
        hold.countDown();
    }

    @Test
    void blockedWorkersDoNotPauseThePartitionAndEveryRecordIsProcessed() throws Exception {
        var release = new CountDownLatch(1);
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
                .thenAnswer(call -> {
                    Runnable accepted = call.getArgument(2);
                    accepted.run();
                    release.await(10, TimeUnit.SECONDS);
                    return MessageApprovalService.Outcome.COMPLETE;
                });
        var subject = consumer(1);
        var acks = new java.util.ArrayList<Acknowledgment>();
        for (int offset = 0; offset < 12; offset++) {
            var ack = mock(Acknowledgment.class);
            acks.add(ack);
            subject.receive(record(offset), ack);
        }

        // Note: worker 4개가 모두 PG 대기로 막혀도 claim 뒤 acknowledge로 상한 자리가 비고, 남은 레코드는 대기열·worker 큐에서
        // 기다린다. 큐 용량이 상한 + worker 수라 제출 거부나 재시도 소진으로 Partition이 정지되지 않는다.
        verify(acks.get(3), timeout(5000)).acknowledge();
        Thread.sleep(300);
        assertThat(count("operation_pause")).isZero();
        release.countDown();
        for (var ack : acks) verify(ack, timeout(10000)).acknowledge();
        // Note: 상한 때문의 일시 정지(capacity_pause)는 정상 동작이다. 운영 정지는 없어야 하고, 다 처리하면 다시 켜진다.
        assertThat(count("operation_pause")).isZero();
        verify(container, timeout(5000).atLeastOnce()).resumePartition(PARTITION);
    }

    @Test
    void failureOfWorkThatLostOwnershipDoesNotPauseThePartition() throws Exception {
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
                .thenAnswer(call -> {
                    ownership.onPartitionsRevokedBeforeCommit(null, List.of(PARTITION));
                    throw new IllegalStateException("after rebalance");
                });
        var subject = consumer(4);
        var ack = mock(Acknowledgment.class);

        subject.receive(record(0), ack);

        await().atMost(Duration.ofSeconds(5)).until(() -> count("dropped_without_ownership") == 1);
        verify(container, never()).pausePartition(PARTITION);
        assertThat(count("operation_pause")).isZero();
        verify(ack, never()).acknowledge();
    }

    private void deferToToss() throws Exception {
        when(service.advance(
                        any(MessageApprovalAttempt.class),
                        any(BooleanSupplier.class),
                        any(Runnable.class),
                        anyBoolean()))
                .thenAnswer(call -> {
                    MessageApprovalAttempt attempt = call.getArgument(0);
                    boolean defer = call.getArgument(3);
                    if (!defer) return MessageApprovalService.Outcome.RETRY;
                    attempt.deferTo(com.sparta.ticket.payment.domain.model.pg.PgProvider.TOSS);
                    return MessageApprovalService.Outcome.DEFER;
                });
    }

    @Test
    void deferredRecordIsForwardedToProviderRetryTopicAndAcknowledgedOnlyAfterBrokerAck() throws Exception {
        deferToToss();
        var sent = new java.util.concurrent.CompletableFuture<
                org.springframework.kafka.support.SendResult<String, String>>();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        when(kafka.send(captor.capture())).thenReturn(sent);
        var subject = consumer(4);
        var ack = mock(Acknowledgment.class);
        var record = record(0);

        subject.receive(record, ack);

        verify(kafka, timeout(2000)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        assertThat(captor.getValue().topic()).isEqualTo(PaymentConfirmRetryConsumers.TOSS_TOPIC);
        assertThat(captor.getValue().key()).isEqualTo(record.key());
        assertThat(captor.getValue().value()).isEqualTo(record.value());
        Thread.sleep(200);
        // Note: 브로커 확인 전에는 원본을 ACK하지 않는다.
        verify(ack, never()).acknowledge();

        sent.complete(null);

        verify(ack, timeout(2000)).acknowledge();
        await().atMost(Duration.ofSeconds(2)).until(() -> count("forwarded") == 1);
    }

    @Test
    void failedForwardIsNotAcknowledgedAndIsRetried() throws Exception {
        deferToToss();
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(
                        java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        var subject = consumer(4);
        var ack = mock(Acknowledgment.class);

        subject.receive(record(0), ack);

        await().atMost(Duration.ofSeconds(5)).until(() -> count("forward_failed") == 1);
        verify(ack, timeout(5000)).acknowledge();
        verify(kafka, org.mockito.Mockito.times(2)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        assertThat(count("operation_pause")).isZero();
    }

    @Test
    void forwardingOffKeepsTheRecordInTheOriginalLane() throws Exception {
        deferToToss();
        var subject = consumerWithoutForwarding(4);
        var ack = mock(Acknowledgment.class);

        subject.receive(record(0), ack);

        await().atMost(Duration.ofSeconds(2)).until(() -> count("local_wait") >= 1);
        verify(kafka, never()).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        verify(ack, never()).acknowledge();
    }

    private PaymentConfirmConsumer consumerWithoutForwarding(int maxInFlight) {
        var properties = new MessageApprovalProperties();
        properties.setEnabled(true);
        properties.setConcurrency(1);
        properties.setMaxPollRecords(1);
        properties.setWorkers(4);
        properties.setPerProvider(2);
        properties.setMaxInFlight(maxInFlight);
        properties.setRetryDelay(Duration.ofMillis(10));
        properties.setForwardOnUnavailable(false);
        ownership.onPartitionsAssigned(null, List.of(PARTITION, OTHER));
        when(registry.getListenerContainer(PaymentConfirmConsumer.ID)).thenReturn(container);
        consumer = new PaymentConfirmConsumer(
                service, properties, ownership, registry, kafka, mapper, meters, Clock.systemDefaultZone());
        return consumer;
    }
}
