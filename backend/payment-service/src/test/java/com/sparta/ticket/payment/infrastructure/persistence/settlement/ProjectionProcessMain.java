package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionCheckpoint;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionRuntime;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 실제 운영 실행기를 별도 JVM에서 구동한다. PG/DB/Kafka는 부모 시험의 일회용 자원이다. */
public final class ProjectionProcessMain {
    private ProjectionProcessMain() {}

    public static void main(String[] args) throws Exception {
        try (var ds = dataSource(args[0], args[1], args[2])) {
            var jdbc = new JdbcTemplate(ds);
            var manager = new DataSourceTransactionManager(ds);
            ProjectionCheckpoint checkpoint = ProjectionCheckpoint.NONE;
            if (args.length > 8 && !"NONE".equals(args[7])) {
                var once = new java.util.concurrent.atomic.AtomicBoolean();
                var selected = ProjectionCheckpoint.Stage.valueOf(args[7]);
                checkpoint = (stage, reference) -> {
                    if (stage != selected || !once.compareAndSet(false, true)) return;
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(args[8]), stage.name());
                        while (!Thread.currentThread().isInterrupted()) Thread.sleep(100);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                };
            }
            if (args.length > 9 && Integer.parseInt(args[9]) > 0) {
                var source = new SettlementSourceJdbc(jdbc);
                var journal = new SettlementFactJdbc(jdbc, source);
                new org.springframework.transaction.support.TransactionTemplate(manager)
                        .executeWithoutResult(status -> {
                            ReconcilePerformanceTest.seed(jdbc, Integer.parseInt(args[9]));
                            source.facts("").forEach(journal::save);
                        });
                checkpoint.hit(ProjectionCheckpoint.Stage.SOURCE_TX_COMMITTED, "source");
            }
            var catalog = ProjectionKafkaIntegrationTest.proxy(
                    new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
            var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
            var baseline = ProjectionKafkaIntegrationTest.proxy(new ProjectionConsumerJdbc(jdbc, sink), manager);
            var config = new ProjectionProperties();
            config.setMode(args.length > 6 ? args[6] : "C");
            config.setPartitions(2);
            config.setBatchSize(500);
            config.setLease(Duration.ofSeconds(2));
            config.setStateDirectory(args[4]);
            config.setTopicPrefix(args[5]);
            var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            ProjectionJvmSampler sampler =
                    args.length > 11 ? new ProjectionJvmSampler(java.nio.file.Path.of(args[11]), meters) : null;
            try (var runtime = new ProjectionRuntime(
                    config,
                    catalog,
                    baseline,
                    sink,
                    args[3],
                    checkpoint,
                    new com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionTelemetry(
                            jdbc, meters))) {
                Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
                while (!Thread.currentThread().isInterrupted()) {
                    runtime.tick();
                    Thread.sleep(args.length > 10 ? Long.parseLong(args[10]) : 100);
                }
            } finally {
                if (sampler != null) sampler.close();
                meters.close();
            }
        }
    }

    static HikariDataSource dataSource(String url, String user, String password) {
        var properties = new HikariConfig();
        properties.setJdbcUrl(url);
        properties.setUsername(user);
        properties.setPassword(password);
        properties.setMaximumPoolSize(20);
        properties.setMinimumIdle(5);
        properties.setConnectionTimeout(5000);
        return new HikariDataSource(properties);
    }
}
