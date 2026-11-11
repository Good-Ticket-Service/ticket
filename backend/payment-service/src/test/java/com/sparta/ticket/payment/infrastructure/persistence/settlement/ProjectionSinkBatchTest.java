package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import com.sparta.ticket.payment.infrastructure.settlement.projection.SettlementProjectionTopology;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionSinkBatchTest {
    @Test
    void pendingMarkerFailureRollsBackAbsoluteTotalsReceiptsAndStates() throws Exception {
        withFixture(f -> {
            f.jdbc()
                    .execute(
                            "CREATE FUNCTION fail_applied_marker() RETURNS trigger LANGUAGE plpgsql AS "
                                    + "'BEGIN IF NEW.applied AND NOT OLD.applied THEN RAISE EXCEPTION ''injected marker failure'' USING ERRCODE=''23514''; END IF; RETURN NEW; END'");
            f.jdbc()
                    .execute("CREATE TRIGGER fail_applied_marker BEFORE UPDATE ON p_settlement_projection_deliveries "
                            + "FOR EACH ROW EXECUTE FUNCTION fail_applied_marker()");
            assertThatThrownBy(() -> f.sink().apply(f.outputs())).isInstanceOf(DataIntegrityViolationException.class);
            for (String table : List.of("totals", "applied", "payment_states"))
                assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_" + table, Long.class))
                        .isZero();
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_deliveries WHERE applied",
                                    Long.class))
                    .isZero();
            f.jdbc().execute("DROP TRIGGER fail_applied_marker ON p_settlement_projection_deliveries");
            f.sink().apply(f.outputs());
            f.sink().apply(f.outputs());
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_deliveries WHERE applied",
                                    Long.class))
                    .isEqualTo(100);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            f.catalog().publishedBatch(f.catalog().claim(100, 30000));
            f.catalog().freeze(f.build());
            assertThat(f.catalog().verify(f.build())).isTrue();
            f.catalog().activate(f.build(), 0);
            assertThat(ProjectionLoadDriver.keyDifferences(f.jdbc())).isZero();
        });
    }

    @Test
    void coalescedHotKeyKeepsAllReceiptsAtomicAcrossFailureReversalAndReplay() throws Exception {
        withFixture(f -> {
            var reversed = new ArrayList<>(f.outputs());
            Collections.reverse(reversed);
            reversed.addAll(f.outputs());
            f.jdbc()
                    .execute(
                            "CREATE FUNCTION fail_sink_receipt() RETURNS trigger LANGUAGE plpgsql AS "
                                    + "'BEGIN RAISE EXCEPTION ''injected receipt failure'' USING ERRCODE=''23514''; RETURN NEW; END'");
            f.jdbc()
                    .execute("CREATE TRIGGER fail_sink_receipt BEFORE INSERT ON p_settlement_projection_applied "
                            + "FOR EACH ROW EXECUTE FUNCTION fail_sink_receipt()");
            assertThatThrownBy(() -> f.sink().apply(reversed)).isInstanceOf(DataIntegrityViolationException.class);
            for (String name : List.of("totals", "applied", "payment_states"))
                assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_" + name, Long.class))
                        .isZero();
            f.jdbc().execute("DROP TRIGGER fail_sink_receipt ON p_settlement_projection_applied");
            f.sink().apply(reversed);
            f.sink().apply(f.outputs());
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            assertThat(
                            f.jdbc()
                                    .queryForMap(
                                            "SELECT sum(sale_count)::bigint sales,sum(refund_count)::bigint refunds,"
                                                    + "sum(sale_amount)::bigint sale_amount,sum(refund_amount)::bigint refund_amount FROM p_settlement_projection_totals"))
                    .containsEntry("sales", 95L)
                    .containsEntry("refunds", 5L)
                    .containsEntry("sale_amount", 835160L)
                    .containsEntry("refund_amount", 44060L);
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_payment_states WHERE pending",
                                    Long.class))
                    .isZero();
            f.catalog().publishedBatch(f.catalog().claim(100, 30000));
            f.catalog().freeze(f.build());
            assertThat(f.catalog().verify(f.build())).isTrue();
            f.catalog().activate(f.build(), 0);
            assertThat(ProjectionLoadDriver.keyDifferences(f.jdbc())).isZero();
        });
    }

    @Test
    void existingAndWithinBatchRevisionConflictsCannotDisappearBehindANewerAbsoluteTotal() throws Exception {
        withFixture(f -> {
            List<ProjectionResult> hot = f.outputs().stream()
                    .filter(r -> "TOTAL".equals(r.type()))
                    .filter(r -> r.key().startsWith("1:1:"))
                    .sorted(Comparator.comparingLong(ProjectionResult::revision))
                    .toList();
            assertThat(hot.size()).isGreaterThan(4);
            f.sink().apply(List.of(hot.get(0)));
            f.sink().apply(List.of(conflict(hot.get(0)), hot.get(1)));
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_errors WHERE reason='TOTAL_REVISION_CONFLICT'",
                                    Long.class))
                    .isEqualTo(1);
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT sale_amount FROM p_settlement_projection_totals WHERE aggregate_key=?",
                                    Long.class,
                                    hot.get(0).key()))
                    .isEqualTo(hot.get(1).totals().saleSellerAmount());
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(2);
            // Note: 같은 poll에서 서로 다른 사실이 같은 revision을 주장하면 최신 값 병합 뒤에도 충돌을 보존한다.
            var third = hot.get(2);
            var forged = new ProjectionResult(
                    third.buildId(),
                    third.type(),
                    third.key(),
                    third.revision(),
                    conflict(third).totals(),
                    hot.get(3).factKey(),
                    hot.get(3).meaningHash(),
                    null,
                    hot.get(3).amount());
            f.sink().apply(List.of(third, forged, hot.get(4)));
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_applied WHERE fact_key=?",
                                    Long.class,
                                    hot.get(3).factKey()))
                    .isZero();
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT revision FROM p_settlement_projection_totals WHERE aggregate_key=?",
                                    Long.class,
                                    third.key()))
                    .isEqualTo(hot.get(4).revision());
        });
    }

    @Test
    void concurrentPollReplaysKeepTheHighestRevisionAndEveryCanonicalReceipt() throws Exception {
        withFixture(f -> {
            f.sink()
                    .apply(f.outputs().stream()
                            .filter(r -> !"TOTAL".equals(r.type()))
                            .toList());
            var totals =
                    f.outputs().stream().filter(r -> "TOTAL".equals(r.type())).toList();
            var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int trial = 0; trial < 4; trial++) {
                    var shuffled = new ArrayList<>(totals.subList(0, totals.size() - trial * 10));
                    Collections.shuffle(shuffled, new java.util.Random(334 + trial));
                    futures.add(executor.submit(() -> {
                        try {
                            start.await();
                            f.sink().apply(shuffled);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }));
                }
                start.countDown();
                for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("동시 sink 재전달 실패", exception);
            } finally {
                executor.shutdownNow();
            }
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(100);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_errors", Long.class))
                    .isZero();
            f.catalog().publishedBatch(f.catalog().claim(100, 30000));
            f.catalog().freeze(f.build());
            assertThat(f.catalog().verify(f.build())).isTrue();
            f.catalog().activate(f.build(), 0);
            assertThat(ProjectionLoadDriver.keyDifferences(f.jdbc())).isZero();
        });
    }

    private static ProjectionResult conflict(ProjectionResult r) {
        var t = r.totals();
        return new ProjectionResult(
                r.buildId(),
                r.type(),
                r.key(),
                r.revision(),
                new SettlementTotals(t.saleCount(), t.saleSellerAmount() + 1, t.refundCount(), t.refundSellerAmount()),
                r.factKey(),
                r.meaningHash(),
                null,
                r.amount());
    }

    private record Fixture(
            JdbcTemplate jdbc,
            ProjectionCatalogJdbc catalog,
            ProjectionSinkJdbc sink,
            UUID build,
            List<ProjectionResult> outputs) {}

    private static void withFixture(Consumer<Fixture> check) throws Exception {
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
                                ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.HOT_KEY));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
                UUID build = catalog.create(UUID.randomUUID(), "C", "test", "coalesced sink financial boundaries");
                catalog.materialize(build);
                catalog.fanout(build);
                var properties = new Properties();
                properties.put("application.id", "sink-batch-test");
                properties.put("bootstrap.servers", "unused:9092");
                try (var driver = new TopologyTestDriver(
                        SettlementProjectionTopology.create(build.toString(), "input", "output"), properties)) {
                    var input = driver.createInputTopic("input", new StringSerializer(), new StringSerializer());
                    var output = driver.createOutputTopic(
                            "output",
                            new StringDeserializer(),
                            ProjectionJson.serde(ProjectionResult.class).deserializer());
                    new SettlementSourceJdbc(jdbc)
                            .facts("").stream()
                                    .sorted(Comparator.comparingInt(
                                            fact -> fact.kind() == SettlementFact.Kind.REFUND ? 0 : 1))
                                    .forEach(fact -> input.pipeInput(
                                            Long.toString(fact.paymentId()), ProjectionJson.write(fact)));
                    check.accept(new Fixture(jdbc, catalog, sink, build, output.readValuesToList()));
                }
            }
        }
    }
}
