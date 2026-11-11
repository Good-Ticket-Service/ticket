package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentRecoveryTransactions;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;

@EnabledIfSystemProperty(named = "payment.confirm.recovery", matches = "true")
class PaymentConfirmProcessRecoveryTest extends PaymentIntegrationTest {
    private static final String TOPIC = PaymentConfirmConsumer.TOPIC;
    private static final String GROUP = "payment-confirm-requested";
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    PaymentService service;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentRecoveryTransactions recovery;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void killsAtFourBoundariesAndRecoversWithoutDuplicateChargesOrLostResults() throws Exception {
        Path output = Path.of("build/payment-confirm-recovery", Long.toString(System.currentTimeMillis()))
                .toAbsolutePath();
        Files.createDirectories(output);
        var fake = new FakePgAdmin(FakePgContainer.baseUrl());
        fake.clearFaults();
        var client = new TossPgClient(RestClient.builder(), properties(), mapper);
        try (var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            broker.start();
            var producer = new DefaultKafkaProducerFactory<String, String>(
                    Map.of("bootstrap.servers", broker.getBootstrapServers(), "acks", "all"),
                    new StringSerializer(),
                    new StringSerializer());
            Process child = null;
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                admin.createTopics(List.of(
                                new NewTopic(TOPIC, 12, (short) 1), new NewTopic(TOPIC + ".DLT", 12, (short) 1)))
                        .all()
                        .get(10, TimeUnit.SECONDS);
                var kafka = new KafkaTemplate<>(producer);
                String[] stages = {"REGISTER", "CLAIM", "PG", "RESULT"};
                var evidence = new LinkedHashMap<String, Object>();
                Payment previous = null;
                String previousStage = null;
                String frozen = null;
                for (int index = 0; index < stages.length; index++) {
                    String stage = stages[index];
                    Payment payment = ready(index);
                    Path marker = output.resolve(stage + ".marker");
                    child = start(broker, stage, payment, marker, output.resolve(stage + ".log"));
                    final Process active = child;
                    final int previousOffset = index;
                    if (previous != null) {
                        await().atMost(WAIT).untilAsserted(() -> {
                            assertThat(active.isAlive()).isTrue();
                            assertThat(offset(admin)).isEqualTo(previousOffset);
                        });
                        recover(previous, previousStage, client);
                        if (previousStage.equals("RESULT"))
                            assertThat(result(previous)).isEqualTo(frozen);
                    }
                    String token = fake.tossCheckout(payment.getOrderId(), 10000);
                    var now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).atOffset(ZoneOffset.ofHours(9));
                    String payload = mapper.writeValueAsString(Map.of(
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
                            10000,
                            "pgAuthToken",
                            token,
                            "confirmBefore",
                            now.plusMinutes(5)));
                    kafka.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    TOPIC, 0, payment.getReservationId().toString(), payload))
                            .get(10, TimeUnit.SECONDS);
                    await().atMost(WAIT).untilAsserted(() -> {
                        assertThat(active.isAlive()).isTrue();
                        assertThat(Files.exists(marker)).isTrue();
                    });
                    assertThat(offset(admin)).isEqualTo(index);
                    assertThat(state(payment))
                            .isEqualTo(
                                    stage.equals("REGISTER")
                                            ? "READY"
                                            : stage.equals("RESULT") ? "DONE" : "IN_PROGRESS");
                    assertThat(fake.transactionStatus(payment.getOrderId()))
                            .isEqualTo(stage.equals("REGISTER") || stage.equals("CLAIM") ? "READY" : "DONE");
                    if (stage.equals("RESULT")) frozen = result(payment);
                    child.destroyForcibly();
                    assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    child = null;
                    evidence.put(
                            stage,
                            Map.of(
                                    "beforeCrashOffset",
                                    index,
                                    "beforeCrashPayment",
                                    state(payment),
                                    "beforeCrashPg",
                                    fake.transactionStatus(payment.getOrderId())));
                    previous = payment;
                    previousStage = stage;
                }
                child = start(broker, "NONE", previous, output.resolve("NONE.marker"), output.resolve("restart.log"));
                final Process active = child;
                await().atMost(WAIT).untilAsserted(() -> {
                    assertThat(active.isAlive()).isTrue();
                    assertThat(offset(admin)).isEqualTo(4);
                });
                assertThat(result(previous)).isEqualTo(frozen);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_payment_result_outbox WHERE order_id LIKE 'crash-confirm-%'",
                                Long.class))
                        .isEqualTo(4);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_payments WHERE order_id LIKE 'crash-confirm-%' AND status='DONE'",
                                Long.class))
                        .isEqualTo(3);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_payments WHERE order_id LIKE 'crash-confirm-%' AND failure_code='NOT_APPROVED'",
                                Long.class))
                        .isEqualTo(1);
                assertThat(FakePgContainer.jdbc()
                                .queryForObject(
                                        "SELECT count(*) FROM pg_transaction WHERE order_id LIKE 'crash-confirm-%' AND status='DONE'",
                                        Long.class))
                        .isEqualTo(3);
                settlementJobs.drain();
                assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                        .isZero();
                evidence.put(
                        "final",
                        Map.of(
                                "committedOffset",
                                4,
                                "resultCount",
                                4,
                                "approved",
                                3,
                                "notApproved",
                                1,
                                "ledgerDifference",
                                0));
                Files.writeString(
                        output.resolve("results.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            } finally {
                if (child != null) {
                    child.destroy();
                    if (!child.waitFor(10, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        child.waitFor(10, TimeUnit.SECONDS);
                    }
                }
                producer.destroy();
                fake.clearFaults();
            }
        }
    }

    private Payment ready(int index) {
        String order = "crash-confirm-" + index + "-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                34_620_000L + index,
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                11000,
                1000,
                LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(10)));
        return payments.findByOrderId(order).orElseThrow();
    }

    private void recover(Payment payment, String stage, TossPgClient client) {
        if (stage.equals("CLAIM") || stage.equals("PG")) {
            paymentClock.reset();
            paymentClock.advance(stage.equals("CLAIM") ? Duration.ofSeconds(61) : Duration.ofSeconds(11));
            var claim = recovery.claimOne(new ArrayList<>(List.of(0L))).orElseThrow();
            assertThat(claim.paymentId()).isEqualTo(payment.getId());
            recovery.apply(
                    claim,
                    client.inquire(new com.sparta.ticket.payment.application.port.pg.PgClient.PgInquireRequest(
                            payment.getOrderId(), claim.transactionId())));
            paymentClock.reset();
        }
        assertThat(state(payment)).isEqualTo(stage.equals("CLAIM") ? "FAILED" : "DONE");
    }

    private String state(Payment payment) {
        return jdbc.queryForObject("SELECT status FROM p_payments WHERE id=?", String.class, payment.getId());
    }

    private String result(Payment payment) {
        return jdbc.queryForObject(
                "SELECT payload FROM p_payment_result_outbox WHERE payment_id=?", String.class, payment.getId());
    }

    private long offset(Admin admin) throws Exception {
        var value = admin.listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(TOPIC, 0));
        return value == null ? 0 : value.offset();
    }

    private PgProperties properties() {
        String url = FakePgContainer.baseUrl();
        return new PgProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                new PgProperties.Toss(url, "test_sk_fake_toss"),
                new PgProperties.Kakao(
                        url,
                        "DEV_fake_kakao",
                        "TC0ONETIME",
                        "http://localhost/approve",
                        "http://localhost/cancel",
                        "http://localhost/fail"));
    }

    private Process start(KafkaContainer broker, String stage, Payment target, Path marker, Path log) throws Exception {
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Xmx384m");
        args.add("-Dfile.encoding=UTF-8");
        args.add("-Dconfirm.checkpoint=" + stage);
        args.add("-Dconfirm.checkpoint.order=" + target.getOrderId());
        args.add("-Dconfirm.checkpoint.payment=" + target.getId());
        args.add("-Dconfirm.checkpoint.marker=" + marker);
        Path classpath = Path.of(System.getProperty("confirm.test.classpath.file"));
        args.add("-cp");
        args.add(Files.readString(classpath));
        args.add(PaymentConfirmProcessMain.class.getName());
        args.add(
                "--spring.config.additional-location=optional:file:../config-repo/application.yml,optional:file:../config-repo/payment-service.yml");
        args.add("--spring.profiles.active=integration");
        args.add("--spring.cloud.config.enabled=false");
        args.add("--eureka.client.enabled=false");
        args.add("--server.port=0");
        args.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
        args.add("--spring.datasource.username=" + POSTGRES.getUsername());
        args.add("--spring.datasource.password=" + POSTGRES.getPassword());
        args.add("--spring.jpa.properties.hibernate.default_schema=public");
        args.add("--spring.jpa.hibernate.ddl-auto=validate");
        args.add("--spring.sql.init.mode=never");
        args.add("--spring.kafka.bootstrap-servers=" + broker.getBootstrapServers());
        args.add("--payment.messaging.confirm.enabled=true");
        args.add("--payment.messaging.confirm.concurrency=1");
        args.add("--payment.messaging.confirm.max-poll-records=1");
        args.add("--payment.messaging.confirm.workers=2");
        args.add("--payment.messaging.confirm.per-provider=1");
        for (String flag : new String[] {
            "payment.messaging.result-relay",
            "payment.recovery",
            "payment.reconcile",
            "payment.recovery.refund",
            "payment.observation",
            "payment.pg",
            "payment.settlement.worker",
            "payment.settlement.backfill",
            "payment.settlement.statement",
            "payment.settlement.payout",
            "payment.settlement.payout.recovery",
            "payment.settlement.reconcile"
        }) args.add("--" + flag + ".enabled=false");
        args.add("--pg.toss.base-url=" + FakePgContainer.baseUrl());
        args.add("--pg.toss.secret-key=test_sk_fake_toss");
        args.add("--pg.circuit-breaker.enabled=false");
        Path argumentsFile = log.resolveSibling(log.getFileName() + ".args");
        Files.write(
                argumentsFile,
                args.subList(1, args.size()).stream()
                        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        .toList());
        return new ProcessBuilder(args.get(0), "@" + argumentsFile)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }
}
