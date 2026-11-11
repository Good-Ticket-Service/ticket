package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

class ProjectionRebuildIntegrationTest {
    @TempDir
    java.nio.file.Path state;

    @Test
    void retainedDbRebuildsExpiredInputAndCatchesNewFactsBeforeReplacingQuarantinedGeneration() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16");
                var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                        .withEnv("KAFKA_LOG_RETENTION_CHECK_INTERVAL_MS", "1000")) {
            postgres.start();
            kafka.start();
            try (var ds = ProjectionProcessMain.dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var connection = ds.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new FileSystemResource("../sql/payment-service/schema.sql"));
                }
                var jdbc = new JdbcTemplate(ds);
                var manager = new DataSourceTransactionManager(ds);
                var tx = new TransactionTemplate(manager);
                tx.executeWithoutResult(
                        status -> ProjectionFixture.seed(jdbc, 0, 100, true, ProjectionFixture.Distribution.UNIFORM));
                var catalog = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(jdbc), manager);
                var baseline = ProjectionKafkaIntegrationTest.proxy(new ProjectionConsumerJdbc(jdbc, sink), manager);
                var source = new SettlementSourceJdbc(jdbc);
                var journal = new SettlementFactJdbc(jdbc, source);
                var config = new ProjectionProperties();
                config.setMode("C");
                config.setPartitions(2);
                config.setTopicPrefix("rebuild." + UUID.randomUUID());
                config.setStateDirectory(state.toString());
                UUID original;
                try (var runtime =
                        new ProjectionRuntime(config, catalog, baseline, sink, kafka.getBootstrapServers())) {
                    ProjectionKafkaIntegrationTest.progress(runtime, () -> catalog.generation() == 1);
                    original = jdbc.queryForObject(
                            "SELECT build_id FROM p_settlement_projection_head WHERE id=1", UUID.class);
                }
                // Note: 시험 소유 build 입력의 실제 retention을 줄이고 segment를 roll해 만료를 기다린다.
                try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                    var latest = new HashMap<TopicPartition, OffsetSpec>();
                    for (int partition = 0; partition < 2; partition++)
                        latest.put(
                                new TopicPartition(config.inputTopic(original.toString()), partition),
                                OffsetSpec.latest());
                    var ends = admin.listOffsets(latest).all().get();
                    var resource =
                            new ConfigResource(ConfigResource.Type.TOPIC, config.inputTopic(original.toString()));
                    var previous =
                            admin.describeConfigs(List.of(resource)).all().get().get(resource);
                    var restore = List.of("retention.ms", "segment.ms").stream()
                            .map(name -> new AlterConfigOp(
                                    new ConfigEntry(name, previous.get(name).value()), AlterConfigOp.OpType.SET))
                            .toList();
                    admin.incrementalAlterConfigs(Map.of(
                                    resource,
                                    List.of(
                                            new AlterConfigOp(
                                                    new ConfigEntry("retention.ms", "1000"), AlterConfigOp.OpType.SET),
                                            new AlterConfigOp(
                                                    new ConfigEntry("segment.ms", "1000"), AlterConfigOp.OpType.SET))))
                            .all()
                            .get();
                    Thread.sleep(1200);
                    var marker = source.facts(" AND t.source_type='SALE' AND t.payment_id=1")
                            .get(0);
                    try (var producer = new KafkaProducer<String, String>(
                            Map.of("bootstrap.servers", kafka.getBootstrapServers(), "acks", "all"),
                            new StringSerializer(),
                            new StringSerializer())) {
                        for (var partition : latest.keySet())
                            producer.send(new ProducerRecord<>(
                                            partition.topic(),
                                            partition.partition(),
                                            "1",
                                            ProjectionJson.write(marker)))
                                    .get();
                    }
                    var earliest = new HashMap<TopicPartition, OffsetSpec>();
                    latest.keySet().forEach(partition -> earliest.put(partition, OffsetSpec.earliest()));
                    long deadline =
                            System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
                    boolean expired = false;
                    while (!expired && System.nanoTime() < deadline) {
                        var starts = admin.listOffsets(earliest).all().get();
                        expired = ends.entrySet().stream()
                                .allMatch(entry -> starts.get(entry.getKey()).offset()
                                        >= entry.getValue().offset());
                        if (!expired) Thread.sleep(100);
                    }
                    assertThat(expired).as("실제 retention 만료로 과거 입력 제거").isTrue();
                    admin.incrementalAlterConfigs(Map.of(resource, restore))
                            .all()
                            .get();
                    admin.listOffsets(earliest)
                            .all()
                            .get()
                            .forEach((partition, offset) -> assertThat(offset.offset())
                                    .isGreaterThanOrEqualTo(ends.get(partition).offset()));
                }
                UUID rebuilt = UUID.randomUUID();
                catalog.create(rebuilt, "C", "test", "recover beyond input retention");
                try (var runtime =
                        new ProjectionRuntime(config, catalog, baseline, sink, kafka.getBootstrapServers())) {
                    ProjectionKafkaIntegrationTest.progress(
                            runtime,
                            () -> "READY".equals(catalog.progress(rebuilt).get("status")));
                    assertThat(catalog.generation()).isEqualTo(1);
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?",
                                    Long.class,
                                    rebuilt))
                            .isEqualTo(100);
                    tx.executeWithoutResult(status -> {
                        ProjectionFixture.seed(jdbc, 95, 100, false, ProjectionFixture.Distribution.UNIFORM);
                        source.facts(" AND t.payment_id>95").forEach(journal::save);
                    });
                    ProjectionKafkaIntegrationTest.progress(
                            runtime,
                            () -> jdbc.queryForObject(
                                            "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?",
                                            Long.class,
                                            original)
                                    == 200);
                    assertThat(catalog.activate(rebuilt, 1)).isEqualTo(2);
                    ProjectionKafkaIntegrationTest.progress(
                            runtime,
                            () -> jdbc.queryForObject(
                                                    "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?",
                                                    Long.class,
                                                    rebuilt)
                                            == 200
                                    && jdbc.queryForObject(
                                                    "SELECT sum(sale_amount-refund_amount) FROM p_settlement_projection_totals WHERE build_id=?",
                                                    Long.class,
                                                    rebuilt)
                                            == 1582200L);
                    assertThat(catalog.progress(original).get("status")).isEqualTo("RETIRED");
                    var fact = source.facts(" AND t.source_type='SALE' AND t.payment_id=1")
                            .get(0);
                    var retry = copy(fact, "after-retention-retry", fact.sellerAmount());
                    var conflict = copy(fact, retry.eventId(), fact.sellerAmount() + 1);
                    var properties = new Properties();
                    properties.put("bootstrap.servers", kafka.getBootstrapServers());
                    properties.put("acks", "all");
                    try (var producer = new KafkaProducer<String, String>(
                            properties, new StringSerializer(), new StringSerializer())) {
                        producer.send(new ProducerRecord<>(
                                        config.inputTopic(rebuilt.toString()), "1", ProjectionJson.write(retry)))
                                .get();
                        producer.send(new ProducerRecord<>(
                                        config.inputTopic(rebuilt.toString()), "1", ProjectionJson.write(conflict)))
                                .get();
                    }
                    // 두 입력은 eventId와 paymentId가 같아 각 repartition에서 순서를 유지한다.
                    ProjectionKafkaIntegrationTest.progress(
                            runtime,
                            () -> jdbc.queryForObject(
                                            "SELECT count(*) FROM p_settlement_projection_errors WHERE build_id=? AND reason='EVENT_ID_CONFLICT'",
                                            Long.class,
                                            rebuilt)
                                    > 0);
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=?",
                                    Long.class,
                                    rebuilt))
                            .isEqualTo(200);
                    assertThat(jdbc.queryForObject(
                                    "SELECT sum(sale_amount-refund_amount) FROM p_settlement_projection_totals WHERE build_id=?",
                                    Long.class,
                                    rebuilt))
                            .isEqualTo(1582200);
                    UUID clean = UUID.randomUUID();
                    catalog.create(clean, "C", "test", "reprocess retained source after quarantine");
                    ProjectionKafkaIntegrationTest.progress(
                            runtime,
                            () -> "READY".equals(catalog.progress(clean).get("status")));
                    assertThat(catalog.generation()).isEqualTo(2);
                    assertThat(catalog.activate(clean, 2)).isEqualTo(3);
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_errors WHERE build_id=?",
                                    Long.class,
                                    clean))
                            .isZero();
                    assertThat(jdbc.queryForObject(
                                    "SELECT sum(sale_amount-refund_amount) FROM p_settlement_projection_totals WHERE build_id=?",
                                    Long.class,
                                    clean))
                            .isEqualTo(1582200);
                    assertThat(jdbc.queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                            .isZero();
                }
            }
        }
    }

    private static SettlementFact copy(SettlementFact fact, String eventId, long amount) {
        return new SettlementFact(
                eventId,
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
}
