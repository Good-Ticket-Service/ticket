package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionDbWorker;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionFactBatchJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionCheckpoint;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 실제 브로커의 EOS 출력과 DB 반영을 확인한다. JVM 강제 종료 시험은 별도다. */
class ProjectionKafkaIntegrationTest {
    @TempDir
    Path state;

    @Test
    void baselineAndStreamsBuildTheSameManifestAndOnlyVerifiedGenerationBecomesVisible() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16");
                var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")) {
            postgres.start();
            kafka.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/payment-service/schema.sql"));
            }
            var jdbc = new JdbcTemplate(ds);
            var manager = new DataSourceTransactionManager(ds);
            new TransactionTemplate(manager).executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 12));
            var catalog =
                    proxy(new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
            var sink = proxy(new ProjectionSinkJdbc(jdbc), manager);
            var baseline = proxy(new ProjectionConsumerJdbc(jdbc, sink), manager);
            var dbWorker = proxy(new ProjectionDbWorker(jdbc, baseline), manager);
            var config = new ProjectionProperties();
            config.setMode("B");
            config.setPartitions(2);
            config.setTopicPrefix("test." + UUID.randomUUID());
            config.setStateDirectory(state.toString());
            try (var runtime = new ProjectionRuntime(
                    config,
                    catalog,
                    baseline,
                    sink,
                    kafka.getBootstrapServers(),
                    ProjectionCheckpoint.NONE,
                    null,
                    dbWorker)) {
                progress(runtime, () -> catalog.generation() == 1);
                UUID original =
                        jdbc.queryForObject("SELECT build_id FROM p_settlement_projection_head WHERE id=1", UUID.class);
                assertTotals(jdbc, original);
                UUID candidate = UUID.randomUUID();
                catalog.create(candidate, "C", "test", "compare actual Kafka pipeline");
                progress(
                        runtime,
                        () -> "READY".equals(catalog.progress(candidate).get("status")));
                assertThat(catalog.generation()).isEqualTo(1);
                assertTotals(jdbc, candidate);
                assertThat(catalog.activate(candidate, 1)).isEqualTo(2);
                runtime.tick();
                assertThat(catalog.progress(original).get("status")).isEqualTo("RETIRED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_errors", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                        .isZero();
                UUID dbCandidate = UUID.randomUUID();
                catalog.create(dbCandidate, "D", "test", "replace transport without changing financial meaning");
                progress(
                        runtime,
                        () -> "READY".equals(catalog.progress(dbCandidate).get("status")));
                assertTotals(jdbc, dbCandidate);
                assertThat(catalog.activate(dbCandidate, 2)).isEqualTo(3);
                runtime.tick();
                assertThat(catalog.progress(candidate).get("status")).isEqualTo("RETIRED");
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(runtime, "producer"))
                        .isNull();
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(runtime, "sinkTask"))
                        .isNull();
                assertThat((java.util.Map<?, ?>)
                                org.springframework.test.util.ReflectionTestUtils.getField(runtime, "processors"))
                        .isEmpty();
            }
        }
    }

    private static void assertTotals(JdbcTemplate jdbc, UUID build) {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?", Long.class, build))
                .isEqualTo(12);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(sale_amount) FROM p_settlement_projection_totals WHERE build_id=?",
                        Long.class,
                        build))
                .isEqualTo(104160);
    }

    static void progress(ProjectionRuntime runtime, BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            runtime.tick();
            Thread.sleep(100);
        }
        assertThat(done.getAsBoolean()).as("제한 시간 내 집계·검증 완료").isTrue();
    }

    static ProjectionFactBatchJdbc factBatch(JdbcTemplate jdbc) {
        return proxy(new ProjectionFactBatchJdbc(jdbc), new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
