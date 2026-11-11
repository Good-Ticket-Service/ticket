package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementPreviewJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionHealthReadTest {
    @Test
    void scopedPendingMatchesIndependentMembershipAfterLateCommitAndSnapshotChanges() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var jdbc = new JdbcTemplate(ds);
                var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                var catalog = new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc));
                var consumer = new ProjectionConsumerJdbc(jdbc, new ProjectionSinkJdbc(jdbc));
                var reader = new SettlementPreviewJdbc(jdbc, new ProjectionProperties());
                UUID build = UUID.randomUUID();
                tx.executeWithoutResult(status -> {
                    ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.UNIFORM);
                    catalog.create(build, "B", "health-test", "pending status and late commit");
                });
                tx.executeWithoutResult(status -> catalog.materialize(build));
                tx.executeWithoutResult(status -> {
                    catalog.fanout(build);
                    consumer.apply(build, new SettlementSourceJdbc(jdbc).facts(""));
                    catalog.publishedBatch(catalog.claim(1000, 30000));
                    catalog.freeze(build);
                });
                Boolean verified = tx.execute(status -> catalog.verify(build));
                assertThat(verified).isTrue();
                tx.executeWithoutResult(status -> catalog.activate(build, 0));
                assertHealth(jdbc, tx, reader, build);
                try (var late = ds.getConnection()) {
                    late.setAutoCommit(false);
                    var lateJdbc = new JdbcTemplate(new SingleConnectionDataSource(late, true));
                    append(lateJdbc, 95);
                    tx.executeWithoutResult(status -> append(jdbc, 190));
                    // Note: 새 거래를 커밋한 다음 snapshot을 떠야 미완료된 옛 XID가 xmax보다 작은 조건을 검증한다.
                    tx.executeWithoutResult(status -> catalog.fanout(build));
                    assertHealth(jdbc, tx, reader, build);
                    tx.executeWithoutResult(status -> {
                        var before =
                                reader.read(1, null, LocalDate.of(2026, 10, 11), LocalDate.of(2026, 11, 10), 0, 20);
                        try {
                            late.commit();
                        } catch (java.sql.SQLException exception) {
                            throw new IllegalStateException(exception);
                        }
                        var after = reader.read(1, null, LocalDate.of(2026, 10, 11), LocalDate.of(2026, 11, 10), 0, 20);
                        assertThat(after).isEqualTo(before);
                    });
                }
                assertThat(jdbc.queryForObject("""
                    SELECT bool_and(f.writer_xid<pg_snapshot_xmax(b.fanout_snapshot::pg_snapshot))
                    FROM p_settlement_projection_facts f CROSS JOIN p_settlement_projection_builds b
                    WHERE f.payment_id>95 AND f.payment_id<=190 AND b.id=?
                    """, Boolean.class, build)).isTrue();
                assertHealth(jdbc, tx, reader, build);
                tx.executeWithoutResult(status -> catalog.publishedBatch(catalog.claim(1000, 30000)));
                assertHealth(jdbc, tx, reader, build);
                String snapshot = jdbc.queryForObject(
                        "SELECT fanout_snapshot FROM p_settlement_projection_builds WHERE id=?", String.class, build);
                jdbc.update("UPDATE p_settlement_projection_builds SET fanout_snapshot=NULL WHERE id=?", build);
                assertHealth(jdbc, tx, reader, build);
                jdbc.update("UPDATE p_settlement_projection_builds SET fanout_snapshot=? WHERE id=?", snapshot, build);
                Integer inserted = tx.execute(status -> catalog.fanout(build));
                assertThat(inserted).isEqualTo(100);
                assertHealth(jdbc, tx, reader, build);
                tx.executeWithoutResult(status -> {
                    catalog.publishedBatch(catalog.claim(1000, 30000));
                    consumer.apply(build, new SettlementSourceJdbc(jdbc).facts(""));
                });
                assertHealth(jdbc, tx, reader, build);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_deliveries WHERE NOT applied",
                                Long.class))
                        .isZero();
                assertThat(ProjectionLoadDriver.keyDifferences(jdbc)).isZero();
            }
        }
    }

    @Test
    void completionMarkerCannotReplaceReceiptOrImmutableSourceVerification() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var jdbc = new JdbcTemplate(ds);
                var manager = new DataSourceTransactionManager(ds);
                new TransactionTemplate(manager)
                        .executeWithoutResult(status ->
                                ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.UNIFORM));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
                var consumer = ProjectionKafkaIntegrationTest.proxy(new ProjectionConsumerJdbc(jdbc, sink), manager);
                UUID build = catalog.create(UUID.randomUUID(), "B", "health-test", "cache is not financial proof");
                catalog.materialize(build);
                catalog.fanout(build);
                consumer.apply(build, new SettlementSourceJdbc(jdbc).facts(""));
                catalog.publishedBatch(catalog.claim(1000, 30000));
                catalog.freeze(build);
                String key =
                        jdbc.queryForObject("SELECT min(fact_key) FROM p_settlement_projection_facts", String.class);
                jdbc.update(
                        "UPDATE p_settlement_projection_deliveries SET applied=false WHERE build_id=? AND fact_key=?",
                        build,
                        key);
                assertThat(catalog.verify(build)).isFalse();
                jdbc.update(
                        "UPDATE p_settlement_projection_deliveries SET applied=true WHERE build_id=? AND fact_key=?",
                        build,
                        key);
                jdbc.update("DELETE FROM p_settlement_projection_applied WHERE build_id=? AND fact_key=?", build, key);
                assertThat(catalog.verify(build)).isFalse();
                jdbc.update(
                        "INSERT INTO p_settlement_projection_applied(build_id,fact_key,meaning_hash) SELECT ?,fact_key,meaning_hash FROM p_settlement_projection_facts WHERE fact_key=?",
                        build,
                        key);
                jdbc.update(
                        "UPDATE p_settlement_projection_deliveries SET seller_id=seller_id+1 WHERE build_id=? AND fact_key=?",
                        build,
                        key);
                assertThat(catalog.verify(build)).isFalse();
                jdbc.update(
                        "UPDATE p_settlement_projection_deliveries d SET seller_id=f.seller_id FROM p_settlement_projection_facts f WHERE d.fact_key=f.fact_key AND d.build_id=?",
                        build);
                assertThat(catalog.verify(build)).isTrue();
                catalog.activate(build, 0);
                assertThat(ProjectionLoadDriver.keyDifferences(jdbc)).isZero();
            }
        }
    }

    private static void append(JdbcTemplate jdbc, int from) {
        ProjectionFixture.seed(jdbc, from, 100, true, ProjectionFixture.Distribution.UNIFORM);
        var source = new SettlementSourceJdbc(jdbc);
        var journal = new SettlementFactJdbc(jdbc, source);
        source.facts(" AND t.payment_id>? AND t.payment_id<=?", from, from + 95).forEach(journal::save);
    }

    private static void assertHealth(
            JdbcTemplate jdbc, TransactionTemplate tx, SettlementPreviewJdbc reader, UUID build) {
        var sellers = new ArrayList<>(jdbc.queryForList(
                "SELECT DISTINCT seller_id FROM p_settlement_projection_facts ORDER BY seller_id LIMIT 4", Long.class));
        sellers.add(1001L);
        for (Long seller : sellers)
            for (Long schedule : java.util.Arrays.asList(null, 1L, 7777L)) {
                for (var date : List.of(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 11, 11))) {
                    tx.executeWithoutResult(status -> {
                        LocalDate to = date.isAfter(LocalDate.of(2026, 11, 10)) ? date : LocalDate.of(2026, 11, 10);
                        var actual = reader.read(seller, schedule, date, to, 0, 20);
                        String filter = " AND f.seller_id=? AND f.business_date BETWEEN ? AND ?"
                                + (schedule == null ? "" : " AND f.schedule_id=?");
                        var args = new ArrayList<Object>(List.of(build, seller, date, to));
                        if (schedule != null) args.add(schedule);
                        Long unpublished = jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_facts f LEFT JOIN p_settlement_projection_deliveries d ON d.build_id=? AND d.fact_key=f.fact_key WHERE d.published_at IS NULL"
                                        + filter,
                                Long.class,
                                args.toArray());
                        Long unapplied = jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_facts f LEFT JOIN p_settlement_projection_applied a ON a.build_id=? AND a.fact_key=f.fact_key WHERE a.fact_key IS NULL"
                                        + filter,
                                Long.class,
                                args.toArray());
                        assertThat(actual.health().unpublished()).isEqualTo(unpublished);
                        assertThat(actual.health().unapplied()).isEqualTo(unapplied);
                    });
                }
            }
    }
}
