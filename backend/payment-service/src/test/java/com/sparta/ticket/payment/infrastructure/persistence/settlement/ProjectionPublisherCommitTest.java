package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionPublisherCommitTest {
    @Test
    void synchronousProducerEnqueueTimeIsIncludedInTheBatchBudget() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var jdbc = new JdbcTemplate(ds);
                initialize(jdbc);
                var manager = new DataSourceTransactionManager(ds);
                new TransactionTemplate(manager)
                        .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 12));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                UUID build = UUID.randomUUID();
                catalog.create(build, "B", "test", "bounded synchronous enqueue");
                catalog.materialize(build);
                catalog.fanout(build);
                var config = new com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties();
                config.setSendTimeout(java.time.Duration.ofMillis(80));
                var producer =
                        new org.apache.kafka.clients.producer.MockProducer<String, String>(
                                true,
                                new org.apache.kafka.common.serialization.StringSerializer(),
                                new org.apache.kafka.common.serialization.StringSerializer()) {
                            @Override
                            public java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
                                    org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {
                                try {
                                    Thread.sleep(30);
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(exception);
                                }
                                return super.send(record);
                            }
                        };
                var sink = new ProjectionSinkJdbc(jdbc);
                try (var runtime = new com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionRuntime(
                        config,
                        catalog,
                        new com.sparta.ticket.payment.infrastructure.persistence.settlement.projection
                                .ProjectionConsumerJdbc(jdbc, sink),
                        sink,
                        "unused")) {
                    org.springframework.test.util.ReflectionTestUtils.setField(runtime, "producer", producer);
                    Integer sent = org.springframework.test.util.ReflectionTestUtils.invokeMethod(runtime, "publish");
                    assertThat(sent).isBetween(0, 4);
                    assertThat(producer.history()).hasSize(sent);
                    assertThat(published(jdbc)).isEqualTo(sent.longValue());
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_deliveries WHERE published_at IS NULL",
                                    Long.class))
                            .isEqualTo(12 - sent.longValue());
                }
            }
        }
    }

    @Test
    void acknowledgementBatchRollsBackTogetherAndCannotCompleteAnotherOwnersLease() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var jdbc = new JdbcTemplate(ds);
                initialize(jdbc);
                var manager = new DataSourceTransactionManager(ds);
                new TransactionTemplate(manager)
                        .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 12));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                UUID build = UUID.randomUUID();
                catalog.create(build, "B", "test", "ack batch boundary");
                catalog.materialize(build);
                catalog.fanout(build);
                var previous = catalog.claim(12, 30000);
                jdbc.update(
                        "UPDATE p_settlement_projection_deliveries SET leased_until=CURRENT_TIMESTAMP-interval '1 second'");
                var current = catalog.claim(12, 30000);
                catalog.publishedBatch(previous);
                assertThat(published(jdbc)).isZero();
                String rejectedKey = current.get(5).factKey();
                jdbc.execute("""
                        CREATE FUNCTION fail_ack_batch() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN IF NEW.fact_key='%s' AND NEW.published_at IS NOT NULL THEN
                          RAISE EXCEPTION 'ack batch failure'; END IF; RETURN NEW; END $$
                        """.formatted(rejectedKey));
                jdbc.execute(
                        "CREATE TRIGGER fail_ack_batch BEFORE UPDATE ON p_settlement_projection_deliveries FOR EACH ROW EXECUTE FUNCTION fail_ack_batch()");
                assertThatThrownBy(() -> catalog.publishedBatch(current)).isInstanceOf(RuntimeException.class);
                assertThat(published(jdbc)).isZero();
                jdbc.execute("DROP TRIGGER fail_ack_batch ON p_settlement_projection_deliveries");
                jdbc.execute("DROP FUNCTION fail_ack_batch()");
                catalog.publishedBatch(current);
                catalog.publishedBatch(previous);
                catalog.publishedBatch(current);
                assertThat(published(jdbc)).isEqualTo(12);
            }
        }
    }

    @Test
    void replayedPendingOutputCannotResurrectAResolvedRefund() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var jdbc = new JdbcTemplate(ds);
                initialize(jdbc);
                var manager = new DataSourceTransactionManager(ds);
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                UUID build = UUID.randomUUID();
                catalog.create(build, "C", "test", "pending revision fence");
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
                var pending = new ProjectionResult(build.toString(), "STATE", "1", 1, null, null, null, null, 1);
                var resolved = new ProjectionResult(build.toString(), "STATE", "1", 2, null, null, null, null, 0);
                sink.apply(List.of(resolved, pending, resolved));
                assertThat(jdbc.queryForObject(
                                "SELECT pending FROM p_settlement_projection_payment_states", Boolean.class))
                        .isFalse();
                assertThat(jdbc.queryForObject(
                                "SELECT revision FROM p_settlement_projection_payment_states", Long.class))
                        .isEqualTo(2);
                sink.apply(List.of(new ProjectionResult(build.toString(), "STATE", "1", 2, null, null, null, null, 1)));
                assertThat(jdbc.queryForObject(
                                "SELECT pending FROM p_settlement_projection_payment_states", Boolean.class))
                        .isFalse();
                assertThat(jdbc.queryForObject("SELECT reason FROM p_settlement_projection_errors", String.class))
                        .isEqualTo("STATE_REVISION_CONFLICT");
            }
        }
    }

    private static long published(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_deliveries WHERE published_at IS NOT NULL", Long.class);
    }

    private static void initialize(JdbcTemplate jdbc) throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
        }
    }
}
