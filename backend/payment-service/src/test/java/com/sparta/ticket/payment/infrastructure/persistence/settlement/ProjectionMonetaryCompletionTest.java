package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionMonetaryCompletionTest {
    @Test
    void monetaryTotalAndBusinessReceiptCommitOrRollbackTogether() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var jdbc = new JdbcTemplate(ds);
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var manager = new DataSourceTransactionManager(ds);
                new TransactionTemplate(manager)
                        .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 12));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
                UUID build = UUID.randomUUID();
                catalog.create(build, "C", "test", "money and receipt atomicity");
                catalog.materialize(build);
                catalog.fanout(build);
                var fact = new SettlementSourceJdbc(jdbc).facts("").get(0);
                // Note: 예전 분기의 독립 완료 메시지는 금액 반영 증거로 받아들이지 않는다.
                sink.apply(List.of(new ProjectionResult(
                        build.toString(),
                        "RECEIPT",
                        fact.aggregateKey(),
                        0,
                        null,
                        fact.sourceFactKey(),
                        fact.meaningHash(),
                        null,
                        fact.sellerAmount())));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT reason FROM p_settlement_projection_errors", String.class))
                        .isEqualTo("UNLINKED_RECEIPT");
                var result = new ProjectionResult(
                        build.toString(),
                        "TOTAL",
                        fact.aggregateKey(),
                        1,
                        SettlementTotals.empty().add(fact),
                        fact.sourceFactKey(),
                        fact.meaningHash(),
                        null,
                        fact.sellerAmount());
                jdbc.execute("""
                        CREATE FUNCTION fail_receipt_write() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'receipt write failure'; END $$
                        """);
                jdbc.execute(
                        "CREATE TRIGGER fail_receipt_write BEFORE INSERT ON p_settlement_projection_applied FOR EACH ROW EXECUTE FUNCTION fail_receipt_write()");
                assertThatThrownBy(() -> sink.apply(List.of(result))).isInstanceOf(RuntimeException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_totals", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                        .isZero();
                jdbc.execute("DROP TRIGGER fail_receipt_write ON p_settlement_projection_applied");
                jdbc.execute("DROP FUNCTION fail_receipt_write()");
                sink.apply(List.of(result, result));
                assertThat(jdbc.queryForObject(
                                "SELECT sum(sale_amount) FROM p_settlement_projection_totals", Long.class))
                        .isEqualTo(fact.sellerAmount());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                        .isEqualTo(1);
                var wrongKey = new ProjectionResult(
                        build.toString(),
                        "TOTAL",
                        "999:999:2026-11-10",
                        1,
                        result.totals(),
                        fact.sourceFactKey(),
                        fact.meaningHash(),
                        null,
                        fact.sellerAmount());
                sink.apply(List.of(wrongKey));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_totals", Long.class))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_projection_errors WHERE reason='SOURCE_MEMBERSHIP_CONFLICT'",
                                Long.class))
                        .isEqualTo(1);
            }
        }
    }
}
