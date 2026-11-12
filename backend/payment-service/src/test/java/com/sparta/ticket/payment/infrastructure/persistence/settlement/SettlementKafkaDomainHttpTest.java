package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.refund.RefundTransactions;
import com.sparta.ticket.payment.application.service.settlement.SettlementStatementTransactions;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** 실제 PG HTTP와 Kafka 금융/집계를 잇는다. 예매 JVM·선점·쿠폰까지의 고객 여정 성능 시험은 아니다. */
@EnabledIfSystemProperty(named = "settlement.kafka.domain", matches = "true")
class SettlementKafkaDomainHttpTest {
    @Test
    void twoHundredApprovalsAndFortyRefundsPreserveHeldReversalReceivableAndProjection() throws Exception {
        long started = System.nanoTime();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("success", false);
        Path output =
                Path.of("build/settlement-benchmark/verification/domain-http-" + System.currentTimeMillis() + ".json");
        try (var postgres = new PostgreSQLContainer<>("postgres:16");
                var kafka = new KafkaContainer("apache/kafka:4.3.1")) {
            postgres.start();
            kafka.start();
            String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "currentSchema=payment_schema";
            var setup = new JdbcTemplate(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            setup.execute("CREATE SCHEMA payment_schema");
            try (var connection =
                    new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
            }
            String pg = FakePgContainer.baseUrl();
            String namespace = "domain." + UUID.randomUUID();
            var options = new ArrayList<>(List.of(
                    "--spring.profiles.active=measurement",
                    "--spring.cloud.config.enabled=false",
                    "--eureka.client.enabled=false",
                    "--spring.datasource.url=" + url,
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.sql.init.mode=never",
                    "--spring.jpa.open-in-view=false",
                    "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "--payment.settlement.posting.transport=KAFKA",
                    "--payment.settlement.posting.kafka.enabled=true",
                    "--payment.settlement.posting.kafka.topic=" + namespace + ".posting",
                    "--payment.settlement.posting.kafka.group=" + namespace + ".posting",
                    "--payment.settlement.projection.enabled=true",
                    "--payment.settlement.projection.mode=B",
                    "--payment.settlement.projection.topic-prefix=" + namespace + ".projection",
                    "--payment.settlement.consumer-recovery.replay-enabled=true",
                    "--pg.toss.base-url=" + pg,
                    "--pg.kakao.base-url=" + pg));
            for (String name : List.of(
                    "payment.recovery",
                    "payment.reconcile",
                    "payment.recovery.refund",
                    "payment.observation",
                    "payment.settlement.worker",
                    "payment.settlement.backfill",
                    "payment.settlement.statement",
                    "payment.settlement.payout",
                    "payment.settlement.payout.recovery",
                    "payment.settlement.reconcile",
                    "payment.pg",
                    "payment.messaging.confirm",
                    "payment.messaging.result-relay")) options.add("--" + name + ".enabled=false");
            var app = new SpringApplication(PaymentServiceApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(options.toArray(String[]::new))) {
                var service = context.getBean(PaymentService.class);
                var refunds = context.getBean(RefundService.class);
                var intake = context.getBean(RefundTransactions.class);
                var statements = context.getBean(SettlementStatementTransactions.class);
                var jdbc = context.getBean(JdbcTemplate.class);
                var admin = new FakePgAdmin(pg);
                var orders = new ArrayList<String>();
                for (int i = 0; i < 200; i++) {
                    String order = namespace + "-" + i;
                    long group = i < 20 ? 777001 : i < 40 ? 777002 : 777003;
                    service.checkout(new CheckoutCommand(
                            order,
                            (long) i + 1,
                            1L,
                            group,
                            1L,
                            group,
                            PgProvider.TOSS,
                            10000,
                            0,
                            LocalDateTime.now().plusMinutes(10),
                            LocalDateTime.of(2026, 11, 7, 19, 0)));
                    service.confirm(order, new ConfirmCommand(admin.tossCheckout(order, 10000), 10000));
                    orders.add(order);
                }
                await(() -> scalar(jdbc, "SELECT count(*) FROM p_settlement_jobs WHERE status='DONE'") == 200);
                assertThat(scalar(jdbc, "SELECT count(*) FROM p_payments WHERE status='DONE'"))
                        .isEqualTo(200);
                for (int i = 0; i < 20; i++) intake.accept(orders.get(i), RefundReason.USER_CANCEL);
                assertThat(scalar(jdbc, "SELECT count(*) FROM p_settlement_items WHERE status='HELD'"))
                        .isEqualTo(20);
                assertThat(scalar(jdbc, "SELECT count(*) FROM p_ledger_transactions WHERE source_type='REFUND'"))
                        .isZero();
                var cutoff = LocalDateTime.now().plusDays(1).withHour(4).withMinute(0);
                assertThat(statements.issue(777001L, 777001L, cutoff))
                        .isEqualTo(SettlementStatementTransactions.Outcome.NONE);
                for (int i = 0; i < 20; i++)
                    assertThat(refunds.refund(orders.get(i), RefundReason.USER_CANCEL)
                                    .refundStatus())
                            .isEqualTo("DONE");
                await(() -> scalar(jdbc, "SELECT count(*) FROM p_settlement_jobs WHERE status='DONE'") == 220);
                assertThat(statements.issue(777002L, 777002L, cutoff))
                        .isEqualTo(SettlementStatementTransactions.Outcome.ISSUED);
                for (int i = 20; i < 40; i++)
                    assertThat(refunds.refund(orders.get(i), RefundReason.USER_CANCEL)
                                    .refundStatus())
                            .isEqualTo("DONE");
                await(() -> scalar(jdbc, "SELECT count(*) FROM p_settlement_jobs WHERE status='DONE'") == 240);
                await(() -> scalar(
                                jdbc,
                                "SELECT count(*) FROM p_settlement_projection_applied a WHERE a.build_id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1)")
                        == 240);
                assertThat(scalar(jdbc, "SELECT count(*) FROM p_ledger_transactions WHERE source_type='SALE'"))
                        .isEqualTo(200);
                assertThat(
                                scalar(
                                        jdbc,
                                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='REFUND' AND action='REVERSE'"))
                        .isEqualTo(20);
                assertThat(
                                scalar(
                                        jdbc,
                                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='REFUND' AND action='RECEIVABLE'"))
                        .isEqualTo(20);
                assertThat(scalar(
                                jdbc,
                                "SELECT count(*) FROM p_settlement_items WHERE kind='DEDUCTION' AND status='OPEN'"))
                        .isEqualTo(20);
                assertThat(scalar(jdbc, "SELECT sum(remaining) FROM p_settlement_items WHERE kind='DEDUCTION'"))
                        .isEqualTo(173600);
                assertThat(
                                scalar(
                                        jdbc,
                                        "SELECT sum(sale_amount-refund_amount) FROM p_settlement_projection_totals WHERE build_id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1)"))
                        .isEqualTo(1388800);
                assertThat(
                                scalar(
                                        jdbc,
                                        "SELECT count(*) FROM (SELECT transaction_id FROM p_ledger_entries GROUP BY transaction_id HAVING sum(debit-credit)<>0) bad"))
                        .isZero();
                assertThat(ProjectionLoadDriver.keyDifferences(jdbc)).isZero();
                assertThat(scalar(jdbc, "SELECT count(*) FROM p_financial_consumer_incidents WHERE status<>'RESOLVED'"))
                        .isZero();
                for (int i = 0; i < 200; i++)
                    assertThat(admin.transactionStatus(orders.get(i))).isEqualTo(i < 40 ? "CANCELED" : "DONE");
                result.putAll(Map.of(
                        "approvals",
                        200,
                        "refunds",
                        40,
                        "heldBeforePgCancellation",
                        20,
                        "reverseJournals",
                        20,
                        "receivableJournals",
                        20,
                        "sourceFacts",
                        scalar(jdbc, "SELECT count(*) FROM p_settlement_projection_facts"),
                        "projectionNet",
                        1388800,
                        "keyDifferences",
                        0,
                        "journalDifference",
                        0));
                result.put(
                        "scope", "실제 checkout/confirm 유스케이스·Toss HTTP·FakePG·금융 Kafka/B 집계. 예매 JVM과 쿠폰·좌석 연동은 포함하지 않음");
                result.put("success", true);
            }
        } finally {
            result.put("totalSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        }
    }

    private static long scalar(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("소규모 실제 금융/집계 30초 제한").isTrue();
    }
}
