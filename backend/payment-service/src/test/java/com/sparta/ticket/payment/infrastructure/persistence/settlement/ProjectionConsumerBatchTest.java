package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.projection.InactiveProjectionException;
import com.sparta.ticket.payment.domain.model.settlement.projection.PaymentProjectionState;
import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionFactConflictException;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionConsumerBatchTest {
    @Test
    void strictBatchDoesNotCommitGoodPrefixOrIdentityWhenAnySourceConflicts() throws Exception {
        withFixture(f -> {
            var first = f.facts().get(0);
            var second = f.facts().get(1);
            assertThatThrownBy(() -> f.consumer()
                            .applyStrict(
                                    f.build(),
                                    List.of(first, copy(second, second.eventId(), second.sellerAmount() + 1))))
                    .isInstanceOf(ProjectionFactConflictException.class);
            for (String table : List.of("event_ids", "payment_states", "applied", "totals"))
                assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_" + table, Long.class))
                        .isZero();
            f.consumer().applyStrict(f.build(), f.facts());
            assertAmounts(f.jdbc());
        });
    }

    @Test
    void failedDecisionDoesNotBurnEventIdentityAndSameEventCanApplyAfterDerivedStateRepair() throws Exception {
        withFixture(f -> {
            var refund = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.REFUND)
                    .findFirst()
                    .orElseThrow();
            var sale = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.SALE && fact.paymentId() == refund.paymentId())
                    .findFirst()
                    .orElseThrow();
            String corrupt = ProjectionJson.write(
                    new PaymentProjectionState(copy(sale, sale.eventId(), sale.sellerAmount() + 1), null, true, false));
            f.jdbc()
                    .update(
                            "INSERT INTO p_settlement_projection_payment_states(build_id,payment_id,payload,pending,revision,meaning_hash) VALUES(?,?,?,false,1,?)",
                            f.build(),
                            sale.paymentId(),
                            corrupt,
                            SettlementFact.hash(corrupt));
            assertThatThrownBy(() -> f.consumer().applyStrict(f.build(), List.of(refund)))
                    .isInstanceOf(ProjectionFactConflictException.class)
                    .hasMessage("REFUND_SOURCE_CONFLICT");
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_event_ids WHERE event_id=?",
                                    Long.class,
                                    refund.eventId()))
                    .isZero();
            String empty = ProjectionJson.write(PaymentProjectionState.empty());
            // Note: 시험이 주입한 파생 상태만 복원한다. 금융 원장/fact를 고치거나 삭제하지 않는다.
            f.jdbc()
                    .update(
                            "UPDATE p_settlement_projection_payment_states SET payload=?,pending=false,revision=0,meaning_hash=? WHERE build_id=? AND payment_id=?",
                            empty,
                            SettlementFact.hash(empty),
                            f.build(),
                            sale.paymentId());
            f.consumer().applyStrict(f.build(), List.of(refund, sale));
            f.consumer().applyStrict(f.build(), f.facts());
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            assertAmounts(f.jdbc());
        });
    }

    @Test
    void inactiveBuildCannotReportSuccessfulConsumption() throws Exception {
        withFixture(f -> {
            f.jdbc().update("UPDATE p_settlement_projection_builds SET status='FAILED' WHERE id=?", f.build());
            assertThatThrownBy(() -> f.consumer().applyStrict(f.build(), f.facts()))
                    .isInstanceOf(InactiveProjectionException.class);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isZero();
        });
    }

    @Test
    void pendingMarkerFailureRollsBackReceiptsMoneyAndBusinessIdentity() throws Exception {
        withFixture(f -> {
            f.jdbc()
                    .execute(
                            "CREATE FUNCTION fail_applied_marker() RETURNS trigger LANGUAGE plpgsql AS "
                                    + "'BEGIN IF NEW.applied AND NOT OLD.applied THEN RAISE EXCEPTION ''injected marker failure'' USING ERRCODE=''23514''; END IF; RETURN NEW; END'");
            f.jdbc()
                    .execute("CREATE TRIGGER fail_applied_marker BEFORE UPDATE ON p_settlement_projection_deliveries "
                            + "FOR EACH ROW EXECUTE FUNCTION fail_applied_marker()");
            assertThatThrownBy(() -> f.consumer().apply(f.build(), f.facts()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (String table : List.of("event_ids", "payment_states", "applied", "totals"))
                assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_" + table, Long.class))
                        .isZero();
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_deliveries WHERE applied",
                                    Long.class))
                    .isZero();
            f.jdbc().execute("DROP TRIGGER fail_applied_marker ON p_settlement_projection_deliveries");
            f.consumer().apply(f.build(), f.facts());
            f.consumer().apply(f.build(), f.facts());
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_deliveries WHERE applied",
                                    Long.class))
                    .isEqualTo(100);
            assertAmounts(f.jdbc());
        });
    }

    @Test
    void refundFirstBatchAndBothKindsOfDuplicatesRecoverAtomicallyAfterLateSqlFailure() throws Exception {
        withFixture(f -> {
            var batch = new ArrayList<>(f.facts().stream()
                    .sorted(Comparator.comparingInt(fact -> fact.kind() == SettlementFact.Kind.REFUND ? 0 : 1))
                    .toList());
            batch.add(batch.get(0));
            batch.add(copy(
                    batch.get(0), UUID.randomUUID().toString(), batch.get(0).sellerAmount()));
            f.jdbc()
                    .execute(
                            "CREATE FUNCTION fail_batch_total() RETURNS trigger LANGUAGE plpgsql AS "
                                    + "'BEGIN RAISE EXCEPTION ''injected total failure'' USING ERRCODE=''23514''; RETURN NEW; END'");
            f.jdbc()
                    .execute("CREATE TRIGGER fail_batch_total BEFORE INSERT OR UPDATE ON "
                            + "p_settlement_projection_totals FOR EACH ROW EXECUTE FUNCTION fail_batch_total()");
            assertThatThrownBy(() -> f.consumer().apply(f.build(), batch))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (String table : List.of("event_ids", "payment_states", "applied", "totals"))
                assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_" + table, Long.class))
                        .isZero();
            f.jdbc().execute("DROP TRIGGER fail_batch_total ON p_settlement_projection_totals");
            f.consumer().apply(f.build(), batch);
            f.consumer().apply(f.build(), batch);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_event_ids", Long.class))
                    .isEqualTo(101);
            assertAmounts(f.jdbc());
            f.catalog().publishedBatch(f.catalog().claim(100, 30000));
            f.catalog().freeze(f.build());
            assertThat(f.catalog().verify(f.build())).isTrue();
            f.catalog().activate(f.build(), 0);
            assertThat(ProjectionLoadDriver.keyDifferences(f.jdbc())).isZero();
        });
    }

    @Test
    void invalidMembershipCannotReserveEventIdentityAndConflictingIdentityCannotAddMoney() throws Exception {
        withFixture(f -> {
            var sales = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.SALE)
                    .toList();
            var first = sales.get(0);
            var second = sales.get(1);
            f.consumer()
                    .apply(
                            f.build(),
                            List.of(
                                    copy(first, first.eventId(), first.sellerAmount() + 1),
                                    first,
                                    copy(second, first.eventId(), second.sellerAmount())));
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(1);
            assertThat(f.jdbc()
                            .queryForList(
                                    "SELECT reason FROM p_settlement_projection_errors ORDER BY reason", String.class))
                    .containsExactly("EVENT_ID_CONFLICT", "SOURCE_MEMBERSHIP_CONFLICT");
            f.consumer().apply(f.build(), f.facts());
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            assertAmounts(f.jdbc());
            f.catalog().publishedBatch(f.catalog().claim(100, 30000));
            f.catalog().freeze(f.build());
            assertThat(f.catalog().verify(f.build())).as("미해결 충돌은 READY 전환 금지").isFalse();
        });
    }

    private static void assertAmounts(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForMap("SELECT sum(sale_count)::bigint sales,sum(refund_count)::bigint refunds,"
                        + "sum(sale_amount)::bigint sale_amount,sum(refund_amount)::bigint refund_amount "
                        + "FROM p_settlement_projection_totals"))
                .containsEntry("sales", 95L)
                .containsEntry("refunds", 5L)
                .containsEntry("sale_amount", 835160L)
                .containsEntry("refund_amount", 44060L);
        assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                .isZero();
    }

    private static SettlementFact copy(SettlementFact fact, String event, long amount) {
        return new SettlementFact(
                event,
                fact.sourceFactKey(),
                fact.kind(),
                fact.action(),
                fact.paymentId(),
                fact.refundId(),
                fact.sellerId(),
                fact.scheduleId(),
                fact.occurredAt(),
                amount,
                fact.policyVersion());
    }

    record Fixture(
            JdbcTemplate jdbc,
            ProjectionCatalogJdbc catalog,
            ProjectionConsumerJdbc consumer,
            UUID build,
            List<SettlementFact> facts) {}

    static void withFixture(Consumer<Fixture> check) throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            var admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            admin.execute("CREATE ROLE projection_fixture_app LOGIN PASSWORD 'fixture_only' NOSUPERUSER");
            admin.execute("CREATE SCHEMA payment_schema AUTHORIZATION projection_fixture_app");
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                            + "currentSchema=payment_schema",
                    "projection_fixture_app",
                    "fixture_only")) {
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
                UUID build = catalog.create(UUID.randomUUID(), "B", "test", "batch financial semantics");
                catalog.materialize(build);
                catalog.fanout(build);
                check.accept(new Fixture(jdbc, catalog, consumer, build, new SettlementSourceJdbc(jdbc).facts("")));
            }
        }
    }
}
