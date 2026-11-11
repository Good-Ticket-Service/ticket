package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ProjectionTelemetryTest {
    @Test
    void monitoringFailureKeepsLastValuesAndDoesNotPretendTheBacklogIsZero() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var fail = new AtomicBoolean();
                var jdbc = new JdbcTemplate(ds) {
                    @Override
                    public java.util.Map<String, Object> queryForMap(String sql) {
                        if (fail.get())
                            throw new org.springframework.dao.DataAccessResourceFailureException("metrics unavailable");
                        return super.queryForMap(sql);
                    }
                };
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var manager = new DataSourceTransactionManager(ds);
                new TransactionTemplate(manager)
                        .executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 12));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                UUID build = UUID.randomUUID();
                catalog.create(build, "C", "test", "bounded monitoring");
                catalog.materialize(build);
                catalog.fanout(build);
                var telemetry = new ProjectionTelemetry(jdbc, meters);
                assertThat(gauge(meters, "unpublished")).isNaN();
                telemetry.refresh();
                assertThat(gauge(meters, "unpublished")).isEqualTo(12);
                double sampled = gauge(meters, "sample.timestamp");
                fail.set(true);
                ReflectionTestUtils.setField(telemetry, "nextRefresh", 0L);
                telemetry.refresh();
                assertThat(gauge(meters, "unpublished")).isEqualTo(12);
                assertThat(gauge(meters, "sample.timestamp")).isEqualTo(sampled);
                assertThat(meters.get("payment.settlement.projection.failures")
                                .tag("phase", "METRICS")
                                .counter()
                                .count())
                        .isEqualTo(1);
                fail.set(false);
                catalog.publishedBatch(catalog.claim(12, 30000));
                ReflectionTestUtils.setField(telemetry, "nextRefresh", 0L);
                telemetry.refresh();
                assertThat(gauge(meters, "unpublished")).isZero();
                assertThat(gauge(meters, "active")).isZero();
                assertThat(meters.getMeters())
                        .allSatisfy(meter -> assertThat(meter.getId().getTags())
                                .noneSatisfy(tag -> assertThat(tag.getKey()).isIn("sellerId", "paymentId", "buildId")));
            }
        } finally {
            meters.close();
        }
    }

    private static double gauge(SimpleMeterRegistry meters, String suffix) {
        return meters.get("payment.settlement.projection." + suffix).gauge().value();
    }
}
