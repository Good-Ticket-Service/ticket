package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

class PaymentResultOutboxIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(90_000_000);

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentResultOutboxJdbc outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM p_payment_result_outbox");
    }

    private String ready(PgProvider provider) {
        String order = "outbox-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                provider,
                10000,
                0,
                LocalDateTime.now(paymentClock).plusMinutes(10)));
        return order;
    }

    private String approved() {
        String order = ready(PgProvider.TOSS);
        service.confirm(order, new ConfirmCommand("token-" + UUID.randomUUID(), 10000));
        return order;
    }

    private String payload(String order) {
        return jdbc.queryForObject("SELECT payload FROM p_payment_result_outbox WHERE order_id=?", String.class, order);
    }

    @Test
    void initialResultIsFrozenAcrossReplayAndRefund() throws Exception {
        var approvedAt = LocalDateTime.now(paymentClock).withNano(123456789);
        pg.approveReturns(request -> new PgResult.Approved(request.transactionId(), request.amount(), approvedAt));
        String order = approved();
        String first = payload(order);
        var message = mapper.readTree(first);
        assertThat(message.path("status").asText()).isEqualTo("DONE");
        assertThat(message.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.path("occurredAt").asText()).endsWith("+09:00");
        assertThat(message.path("amount").asInt()).isEqualTo(10000);
        assertThat(payments.findByOrderId(order).orElseThrow().getApprovedAt().getNano())
                .isEqualTo(123456000);
        paymentClock.advance(Duration.ofSeconds(20));
        tx.executeWithoutResult(status -> {
            var payment = payments.lockByOrderId(order).orElseThrow();
            outbox.paymentFinalized(payment);
            payment.cancel();
            outbox.paymentFinalized(payment);
        });
        assertThat(payload(order)).isEqualTo(first);
        assertThat(outbox.pending()).isEqualTo(1);
    }

    @Test
    void failedResultRemainsFailedWhenLateApprovalRequiresAutoVoid() throws Exception {
        String order = ready(PgProvider.TOSS);
        pg.approveReturns(request -> new PgResult.Unknown("response-lost"));
        service.confirm(order, new ConfirmCommand("token-" + UUID.randomUUID(), 10000));
        assertThat(outbox.pending()).isZero();
        tx.executeWithoutResult(status -> transactions.failForRecovery(
                payments.lockByOrderId(order).orElseThrow(),
                com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode.PG_UNRESOLVED));
        String first = payload(order);
        var payment = payments.findByOrderId(order).orElseThrow();
        transactions.complete(
                payment.getId(),
                new PgResult.Approved(payment.getPgTransactionId(), 10000, LocalDateTime.now(paymentClock)));
        assertThat(payload(order)).isEqualTo(first);
        assertThat(mapper.readTree(first).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_refunds WHERE payment_id=? AND reason='AUTO_VOID'",
                        Long.class,
                        payment.getId()))
                .isEqualTo(1);
    }

    @Test
    void outboxInsertFailureRollsBackApprovalResult() {
        String order = ready(PgProvider.TOSS);
        jdbc.execute("ALTER TABLE p_payment_result_outbox ADD CONSTRAINT injected_outbox_failure CHECK(false)");
        try {
            service.confirm(order, new ConfirmCommand("token-" + UUID.randomUUID(), 10000));
            assertThat(payments.findByOrderId(order).orElseThrow().getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(outbox.pending()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE p_payment_result_outbox DROP CONSTRAINT injected_outbox_failure");
        }
        var payment = payments.findByOrderId(order).orElseThrow();
        transactions.complete(
                payment.getId(),
                new PgResult.Approved(payment.getPgTransactionId(), 10000, LocalDateTime.now(paymentClock)));
        assertThat(payments.findByOrderId(order).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(outbox.pending()).isEqualTo(1);
    }

    @Test
    void preparationFailureAndSupersededReadyAlsoPublish() {
        pg.readyReturns(() -> new PgReadyResult.Failed("READY_FAILED"));
        String failed = ready(PgProvider.KAKAO);
        assertThat(payload(failed)).contains("FAILED");
        String previous = ready(PgProvider.TOSS);
        var payment = payments.findByOrderId(previous).orElseThrow();
        service.checkout(new CheckoutCommand(
                "replacement-" + UUID.randomUUID(),
                payment.getReservationId(),
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                10000,
                0,
                payment.getExpiresAt()));
        assertThat(payload(previous)).contains("SUPERSEDED");
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldOwnerCannotFinish() {
        approved();
        var old = outbox.claim(1, Duration.ofSeconds(30)).get(0);
        assertThat(outbox.claim(1, Duration.ofSeconds(30))).isEmpty();
        jdbc.update("UPDATE p_payment_result_outbox SET lease_until=CURRENT_TIMESTAMP-interval '1 second'");
        var current = outbox.claim(1, Duration.ofSeconds(30)).get(0);
        assertThat(current.eventId()).isEqualTo(old.eventId());
        assertThat(current.payload()).isEqualTo(old.payload());
        assertThat(outbox.published(old)).isFalse();
        outbox.failed(old, "STALE_OWNER", Duration.ZERO);
        assertThat(outbox.published(current)).isTrue();
        assertThat(outbox.pending()).isZero();
    }

    @Test
    void brokerFailureKeepsPayloadAndRelayDoesNotHoldDbTransaction() {
        String order = approved();
        String first = payload(order);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
        });
        var relay = relay(kafka);
        assertThat(relay.publishOnce()).isZero();
        assertThat(outbox.pending()).isEqualTo(1);
        assertThat(payload(order)).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM p_payment_result_outbox WHERE order_id=?", String.class, order))
                .isEqualTo("DELIVERY_UNCERTAIN");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> relay.publishOnce()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realKafkaReceivesFrozenResultAndAckLossReplaysSameEvent() throws Exception {
        String order = approved();
        try (var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            broker.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                admin.createTopics(java.util.List.of(new NewTopic("payment.result", 12, (short) 1)))
                        .all()
                        .get();
            }
            var factory = new DefaultKafkaProducerFactory<String, String>(
                    Map.of("bootstrap.servers", broker.getBootstrapServers(), "acks", "all"),
                    new StringSerializer(),
                    new StringSerializer());
            var kafka = new KafkaTemplate<>(factory);
            try (var consumer = new KafkaConsumer<String, String>(
                    Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            broker.getBootstrapServers(),
                            ConsumerConfig.GROUP_ID_CONFIG,
                            "result-test-" + UUID.randomUUID(),
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest",
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                            false),
                    new StringDeserializer(),
                    new StringDeserializer())) {
                var delivery = outbox.claim(1, Duration.ofSeconds(30)).get(0);
                kafka.send("payment.result", delivery.key(), delivery.payload()).get();
                // Note: broker ACK 뒤 완료 기록 전에 죽은 상태를 만든다. 첫 전달은 실제로 broker에 보존돼 있다.
                jdbc.update("UPDATE p_payment_result_outbox SET lease_until=CURRENT_TIMESTAMP-interval '1 second'");
                assertThat(relay(kafka).publishOnce()).isEqualTo(1);
                consumer.subscribe(java.util.List.of("payment.result"));
                var received =
                        new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (received.size() < 2 && System.nanoTime() < deadline) {
                    consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                }
                assertThat(received).hasSize(2);
                assertThat(received).allSatisfy(record -> {
                    assertThat(record.key()).isEqualTo(delivery.key());
                    assertThat(record.value()).isEqualTo(payload(order));
                });
                assertThat(outbox.pending()).isZero();
            } finally {
                factory.destroy();
            }
        }
    }

    private PaymentResultRelay relay(KafkaTemplate<String, String> kafka) {
        return new PaymentResultRelay(
                outbox,
                kafka,
                new PaymentResultRelayProperties(
                        10, Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(1)),
                new SimpleMeterRegistry());
    }
}
