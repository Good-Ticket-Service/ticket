package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.service.payment.SettlementPostingProperties;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobTransport;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.JdbcSettlementJobQueue;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementPostingOutboxJdbc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class SettlementPostingOutboxMigrationTest {
    @Test
    void ownerMigratesLegacyJobsAndPreservesImmutableAtomicIntent() throws Exception {
        String migration = Files.readString(
                        Path.of("../sql/payment-service/migrations/026-settlement-posting-outbox.sql"))
                .replace("\r\n", "\n");
        String schema =
                Files.readString(Path.of("../sql/payment-service/schema.sql")).replace("\r\n", "\n");
        assertThat(schema).containsOnlyOnce(migration);
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var admin = new JdbcTemplate(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            admin.execute("CREATE ROLE posting_app LOGIN PASSWORD 'fixture_only' NOSUPERUSER");
            admin.execute("CREATE SCHEMA payment_schema AUTHORIZATION posting_app");
            var ds = new DriverManagerDataSource(
                    postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                            + "currentSchema=payment_schema",
                    "posting_app",
                    "fixture_only");
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(
                                new ByteArrayResource(schema.substring(0, schema.indexOf(migration))
                                        .getBytes(StandardCharsets.UTF_8)),
                                StandardCharsets.UTF_8));
            }
            var jdbc = new JdbcTemplate(ds);
            jdbc.update("""
                INSERT INTO p_payments(id,order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,
                  pg_provider,expires_at,total_price,discount_amount,amount,status,created_at,created_by,updated_at,updated_by)
                SELECT g,'posting-'||g,g,1,1,1,1,'TOSS',LOCALTIMESTAMP+interval '1 hour',10000,1000,9000,'READY',
                  LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST' FROM generate_series(1,2) g
                """);
            jdbc.update("""
                INSERT INTO p_settlement_jobs(payment_id,reason,status,attempts,next_attempt_at,done_at,created_at,created_by,updated_at,updated_by)
                VALUES(1,'PAYMENT_APPROVED','PENDING',2,LOCALTIMESTAMP,NULL,LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST'),
                  (1,'REFUND_COMPLETED','DONE',0,LOCALTIMESTAMP,LOCALTIMESTAMP,LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """);
            var before = jdbc.queryForList(
                    "SELECT id,payment_id,reason,status,attempts,next_attempt_at,done_at FROM p_settlement_jobs ORDER BY id");
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(
                                new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)),
                                StandardCharsets.UTF_8));
            }
            assertThat(
                            jdbc.queryForList(
                                    "SELECT id,payment_id,reason,status,attempts,next_attempt_at,done_at FROM p_settlement_jobs ORDER BY id"))
                    .isEqualTo(before);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_jobs WHERE transport='DB' AND event_payload IS NOT NULL",
                            Long.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                            "SELECT processing_result FROM p_settlement_jobs WHERE status='DONE'", String.class))
                    .isEqualTo("LEGACY");
            assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class))
                    .isFalse();
            var queue = new JdbcSettlementJobQueue(
                    jdbc, () -> Optional.of("TEST"), new SettlementPostingProperties(SettlementJobTransport.KAFKA));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(
                    status -> queue.enqueue(2L, SettlementJobReason.PAYMENT_APPROVED, LocalDateTime.now()));
            var original = jdbc.queryForMap("SELECT event_id,event_payload FROM p_settlement_jobs WHERE payment_id=2");
            tx.executeWithoutResult(
                    status -> queue.enqueue(2L, SettlementJobReason.PAYMENT_APPROVED, LocalDateTime.now()));
            assertThat(jdbc.queryForMap("SELECT event_id,event_payload FROM p_settlement_jobs WHERE payment_id=2"))
                    .isEqualTo(original);
            assertThat(jdbc.queryForObject(
                            "SELECT (event_payload::jsonb->>'jobId')::bigint=id AND (event_payload::jsonb->>'paymentId')::bigint=payment_id FROM p_settlement_jobs WHERE payment_id=2",
                            Boolean.class))
                    .isTrue();
            var outbox = new SettlementPostingOutboxJdbc(jdbc);
            var firstLease = tx.execute(status -> outbox.claim(10, Duration.ofSeconds(30)));
            assertThat(firstLease).hasSize(1);
            var occupied = tx.execute(status -> outbox.claim(10, Duration.ofSeconds(30)));
            assertThat(occupied).isEmpty();
            jdbc.update(
                    "UPDATE p_settlement_jobs SET publish_lease_until=LOCALTIMESTAMP-interval '1 second' WHERE payment_id=2");
            var secondLease = tx.execute(status -> outbox.claim(10, Duration.ofSeconds(30)));
            assertThat(secondLease).hasSize(1);
            assertThat(secondLease.get(0).token())
                    .isNotEqualTo(firstLease.get(0).token());
            assertThat(secondLease.get(0).eventId()).isEqualTo(firstLease.get(0).eventId());
            int stalePublished = tx.execute(status -> outbox.published(firstLease));
            assertThat(stalePublished).isZero();
            tx.executeWithoutResult(status -> outbox.failed(firstLease, "DELIVERY_UNCERTAIN", Duration.ofSeconds(1)));
            assertThat(jdbc.queryForObject(
                            "SELECT publish_lease_token FROM p_settlement_jobs WHERE payment_id=2",
                            java.util.UUID.class))
                    .isEqualTo(secondLease.get(0).token());
            int published = tx.execute(status -> outbox.published(secondLease));
            assertThat(published).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM p_settlement_jobs WHERE payment_id=2", String.class))
                    .isEqualTo("PENDING");
            assertThatThrownBy(() -> jdbc.update("UPDATE p_settlement_jobs SET transport='DB' WHERE payment_id=2"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() ->
                            jdbc.update("UPDATE p_settlement_jobs SET event_id=gen_random_uuid() WHERE payment_id=2"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        queue.enqueue(2L, SettlementJobReason.REFUND_COMPLETED, LocalDateTime.now());
                        throw new IllegalStateException("호출자 TX 롤백");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_jobs WHERE payment_id=2", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT amount FROM p_payments"))
                    .allSatisfy(row -> assertThat(row.get("amount")).isEqualTo(9000));
        }
    }
}
