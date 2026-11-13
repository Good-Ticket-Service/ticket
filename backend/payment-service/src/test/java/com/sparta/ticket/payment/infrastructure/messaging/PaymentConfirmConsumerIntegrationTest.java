package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.infrastructure.pg.PgBulkheadProperties;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.testcontainers.kafka.KafkaContainer;

class PaymentConfirmConsumerIntegrationTest extends PaymentIntegrationTest {
    private static final String GROUP = "payment-confirm-integration";
    private static final String TOPIC = PaymentConfirmConsumer.TOPIC;

    @Autowired
    PaymentService checkout;

    @Autowired
    MessageApprovalService service;

    @Autowired
    PaymentRepository payments;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MeterRegistry meters;

    @Test
    void poisonDltFailureDuplicateAndActualRebalanceProtectOffsetsAndFinancialEffect() throws Exception {
        try (var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            broker.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                admin.createTopics(List.of(
                                new NewTopic(TOPIC, 12, (short) 1), new NewTopic(TOPIC + ".DLT", 12, (short) 1)))
                        .all()
                        .get(10, TimeUnit.SECONDS);
                var producer = new DefaultKafkaProducerFactory<String, String>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        broker.getBootstrapServers(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.ACKS_CONFIG,
                        "all"));
                var failDlt = new AtomicBoolean(true);
                var template = new KafkaTemplate<String, String>(producer) {
                    @Override
                    public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
                        if (record.topic().endsWith(".DLT") && failDlt.get())
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("synthetic DLT send failure"));
                        return super.send(record);
                    }
                };
                var first = start(broker.getBootstrapServers(), template);
                Running second = null;
                var release = new CountDownLatch(1);
                try {
                    template.send(new ProducerRecord<>(TOPIC, 0, "12", "{broken-sensitive-auth-token"))
                            .get(10, TimeUnit.SECONDS);
                    await().atMost(Duration.ofSeconds(15)).until(() -> counter("dlt_failed") > 0);
                    assertThat(offset(admin, 0)).isZero();
                    assertThat(pg.approveCalls()).isZero();
                    failDlt.set(false);
                    await().atMost(Duration.ofSeconds(15))
                            .untilAsserted(() -> assertThat(offset(admin, 0)).isEqualTo(1));
                    try (var dlt = new KafkaConsumer<String, String>(Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            broker.getBootstrapServers(),
                            ConsumerConfig.GROUP_ID_CONFIG,
                            "dlt-review-" + UUID.randomUUID(),
                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class,
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class,
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                            false,
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest"))) {
                        dlt.assign(List.of(new TopicPartition(TOPIC + ".DLT", 0)));
                        dlt.seekToBeginning(List.of(new TopicPartition(TOPIC + ".DLT", 0)));
                        var records = dlt.poll(Duration.ofSeconds(10));
                        assertThat(records.count()).isEqualTo(1);
                        var value = records.iterator().next();
                        assertThat(value.key()).isEqualTo("12");
                        assertThat(value.value())
                                .contains("INVALID_CONFIRM_MESSAGE", "payloadHash")
                                .doesNotContain("sensitive-auth-token", "pgAuthToken");
                        assertThat(value.headers().toArray()).isEmpty();
                    }
                    var normal = ready(34_610_001);
                    String normalMessage = message(normal);
                    template.send(new ProducerRecord<>(
                                    TOPIC, 0, normal.getReservationId().toString(), normalMessage))
                            .get(10, TimeUnit.SECONDS);
                    template.send(new ProducerRecord<>(
                                    TOPIC, 0, normal.getReservationId().toString(), normalMessage))
                            .get(10, TimeUnit.SECONDS);
                    await().atMost(Duration.ofSeconds(15))
                            .untilAsserted(() -> assertThat(offset(admin, 0)).isEqualTo(3));
                    assertThat(payments.findById(normal.getId()).orElseThrow().getStatus())
                            .isEqualTo(PaymentStatus.DONE);
                    assertThat(pg.approveCalls()).isEqualTo(1);

                    var moving = ready(34_610_002);
                    var entered = new CountDownLatch(1);
                    pg.approveReturns(request -> {
                        entered.countDown();
                        try {
                            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted");
                        }
                        return new PgResult.Approved(
                                request.transactionId(), request.amount(), LocalDateTime.now(paymentClock));
                    });
                    template.send(new ProducerRecord<>(
                                    TOPIC, 11, moving.getReservationId().toString(), message(moving)))
                            .get(10, TimeUnit.SECONDS);
                    assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                    var oldLease = first.ownership.lease(new TopicPartition(TOPIC, 11));
                    assertThat(oldLease).isNotNull();
                    second = start(broker.getBootstrapServers(), template);
                    await().atMost(Duration.ofSeconds(20))
                            .until(() -> !oldLease.active().get());
                    await().atMost(Duration.ofSeconds(20))
                            .untilAsserted(() -> assertThat(offset(admin, 11)).isEqualTo(1));
                    assertThat(payments.findById(moving.getId()).orElseThrow().getStatus())
                            .isEqualTo(PaymentStatus.IN_PROGRESS);
                    assertThat(pg.approveCalls()).isEqualTo(2);
                    release.countDown();
                    await().atMost(Duration.ofSeconds(10))
                            .untilAsserted(() -> assertThat(payments.findById(moving.getId())
                                            .orElseThrow()
                                            .getStatus())
                                    .isEqualTo(PaymentStatus.DONE));
                    assertThat(pg.approveCalls()).isEqualTo(2);
                } finally {
                    release.countDown();
                    if (second != null) second.close();
                    first.close();
                    producer.destroy();
                }
            }
        }
    }

    private Running start(String bootstrap, KafkaTemplate<String, String> template) throws Exception {
        return start(bootstrap, template, service, mapper, meters, paymentClock);
    }

    static Running start(
            String bootstrap,
            KafkaTemplate<String, String> template,
            MessageApprovalService service,
            ObjectMapper mapper,
            MeterRegistry meters,
            java.time.Clock clock)
            throws Exception {
        var properties = new MessageApprovalProperties();
        properties.setConcurrency(1);
        properties.setMaxPollRecords(4);
        properties.setMaxInfrastructureRetries(10);
        var kafka = new KafkaProperties();
        kafka.setBootstrapServers(List.of(bootstrap));
        var ownership = new PaymentConfirmOwnership();
        var registry = new KafkaListenerEndpointRegistry();
        var consumer =
                new PaymentConfirmConsumer(service, properties, ownership, registry, template, mapper, meters, clock);
        var factory = new PaymentConfirmKafkaConfiguration()
                .paymentConfirmContainerFactory(
                        kafka, properties, ownership, new PgBulkheadProperties(false, 100, 100, Duration.ZERO));
        factory.getContainerProperties().setPollTimeout(100);
        var endpoint = new MethodKafkaListenerEndpoint<String, String>();
        endpoint.setId(PaymentConfirmConsumer.ID);
        endpoint.setGroupId(GROUP);
        endpoint.setTopics(TOPIC);
        endpoint.setBean(consumer);
        endpoint.setMethod(PaymentConfirmConsumer.class.getMethod(
                "receive", org.apache.kafka.clients.consumer.ConsumerRecord.class, Acknowledgment.class));
        var handler = new DefaultMessageHandlerMethodFactory();
        handler.afterPropertiesSet();
        endpoint.setMessageHandlerMethodFactory(handler);
        registry.registerListenerContainer(endpoint, factory, true);
        return new Running(registry, consumer, ownership);
    }

    private Payment ready(long reservation) {
        String order = "consumer-" + UUID.randomUUID();
        checkout.checkout(new CheckoutCommand(
                order,
                reservation,
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                10000,
                1000,
                LocalDateTime.now(paymentClock).plusMinutes(10)));
        return payments.findByOrderId(order).orElseThrow();
    }

    private String message(Payment payment) throws Exception {
        var now = LocalDateTime.now(paymentClock).atOffset(ZoneOffset.ofHours(9));
        return mapper.writeValueAsString(Map.of(
                "eventId",
                UUID.randomUUID().toString(),
                "schemaVersion",
                1,
                "occurredAt",
                now,
                "orderId",
                payment.getOrderId(),
                "reservationId",
                payment.getReservationId(),
                "amount",
                payment.getAmount(),
                "pgAuthToken",
                "auth-" + payment.getOrderId(),
                "confirmBefore",
                now.plusMinutes(5)));
    }

    private double counter(String outcome) {
        var counter = meters.find("payment.confirm.records")
                .tag("outcome", outcome)
                .tag("lane", "main")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private long offset(Admin admin, int partition) throws Exception {
        var value = admin.listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(TOPIC, partition));
        return value == null ? 0 : value.offset();
    }

    record Running(
            KafkaListenerEndpointRegistry registry, PaymentConfirmConsumer consumer, PaymentConfirmOwnership ownership)
            implements AutoCloseable {
        @Override
        public void close() {
            registry.stop();
            consumer.close();
            registry.destroy();
        }
    }
}
