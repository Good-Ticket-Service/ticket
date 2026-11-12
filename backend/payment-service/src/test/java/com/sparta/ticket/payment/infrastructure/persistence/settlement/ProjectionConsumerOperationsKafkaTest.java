package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionDbWorker;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionDeadLetterHandler;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionFailureHandler;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionRuntime;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 작은 실제 브로커 시험으로 B의 별도 Group·격리·운영 재처리 경계를 검증한다. */
class ProjectionConsumerOperationsKafkaTest {
    @Test
    void parallelGroupQuarantinesPoisonAndReplaysCanonicalFactWithoutStreamsSink() throws Exception {
        try (var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")) {
            kafka.start();
            ProjectionConsumerBatchTest.withFixture(f -> {
                var manager = new DataSourceTransactionManager(f.jdbc().getDataSource());
                var recovery =
                        ProjectionKafkaIntegrationTest.proxy(new FinancialConsumerRecoveryJdbc(f.jdbc()), manager);
                var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(f.jdbc()), manager);
                var applier = ProjectionKafkaIntegrationTest.proxy(
                        new ProjectionConsumerJdbc(f.jdbc(), sink, recovery), manager);
                var db = ProjectionKafkaIntegrationTest.proxy(new ProjectionDbWorker(f.jdbc(), applier), manager);
                var outbox = ProjectionKafkaIntegrationTest.proxy(new FinancialReplayOutboxJdbc(f.jdbc()), manager);
                var config = new ProjectionProperties();
                config.setMode("B");
                config.setThreads(2);
                config.setPartitions(4);
                config.setTopicPrefix("ops." + UUID.randomUUID());
                var kafkaConfig = new KafkaProperties();
                kafkaConfig.setBootstrapServers(List.of(kafka.getBootstrapServers()));
                var factory = new DefaultKafkaProducerFactory<String, String>(
                        Map.of("bootstrap.servers", kafka.getBootstrapServers(), "acks", "all"),
                        new StringSerializer(),
                        new StringSerializer());
                var template = new KafkaTemplate<>(factory);
                var meters = new SimpleMeterRegistry();
                var beans = new StaticListableBeanFactory();
                beans.addBean(
                        "projectionFailureHandler",
                        new ProjectionDeadLetterHandler(recovery, config, new ObjectMapper(), template, meters));
                var sourceSale = f.facts().stream()
                        .filter(fact -> fact.kind() == SettlementFact.Kind.SALE && fact.paymentId() % 19 != 0)
                        .findFirst()
                        .orElseThrow();
                var sale = ProjectionJson.read(
                        f.jdbc()
                                .queryForObject(
                                        "SELECT payload FROM p_settlement_projection_facts WHERE fact_key=?",
                                        String.class,
                                        sourceSale.sourceFactKey()),
                        SettlementFact.class);
                // Note: 이전 버전에서 수신만 기록하고 적용하지 못한 파생 marker를 재현한다.
                f.jdbc()
                        .update(
                                "INSERT INTO p_settlement_projection_event_ids(build_id,event_id,meaning_hash) VALUES(?,?,?)",
                                f.build(),
                                sale.eventId(),
                                sale.meaningHash());
                try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
                        var runtime = new ProjectionRuntime(
                                config,
                                f.catalog(),
                                applier,
                                sink,
                                kafka.getBootstrapServers(),
                                new ProjectionTelemetry(f.jdbc(), meters),
                                db,
                                beans.getBeanProvider(ProjectionFailureHandler.class),
                                kafkaConfig)) {
                    await(runtime, () -> applied(f) == 99);
                    var description = admin.describeConsumerGroups(
                                    List.of(config.applicationId(f.build().toString())))
                            .all()
                            .get(10, TimeUnit.SECONDS)
                            .values()
                            .iterator()
                            .next();
                    assertThat(description.members()).hasSize(2);
                    assertThat(description.members().stream()
                                    .mapToInt(member -> member.assignment()
                                            .topicPartitions()
                                            .size())
                                    .sum())
                            .isEqualTo(4);
                    assertThat(ReflectionTestUtils.getField(runtime, "sinkTask"))
                            .isNull();
                    assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS))
                            .doesNotContain(config.outputTopic());
                    String topic = config.inputTopic(f.build().toString());
                    template.send(topic, "bad", "{\"fixtureSecret\":\"never-copy-this\"")
                            .get(10, TimeUnit.SECONDS);
                    template.send(topic, "bad", "null").get(10, TimeUnit.SECONDS);
                    var forged = new SettlementFact(
                            sale.eventId(),
                            sale.sourceFactKey(),
                            sale.kind(),
                            sale.action(),
                            sale.paymentId(),
                            sale.refundId(),
                            sale.sellerId(),
                            sale.scheduleId(),
                            sale.occurredAt(),
                            sale.sellerAmount() + 1,
                            sale.policyVersion());
                    template.send(topic, Long.toString(sale.paymentId()), ProjectionJson.write(forged))
                            .get(10, TimeUnit.SECONDS);
                    await(
                            runtime,
                            () -> f.jdbc()
                                            .queryForObject(
                                                    "SELECT count(*) FROM p_financial_consumer_incidents WHERE dlt_confirmed_at IS NOT NULL",
                                                    Long.class)
                                    == 3);
                    try (var dlt = new KafkaConsumer<String, String>(
                            Map.of(
                                    "bootstrap.servers",
                                    kafka.getBootstrapServers(),
                                    "group.id",
                                    "dlt-audit-" + UUID.randomUUID(),
                                    "auto.offset.reset",
                                    "earliest",
                                    "enable.auto.commit",
                                    false),
                            new StringDeserializer(),
                            new StringDeserializer())) {
                        dlt.subscribe(List.of(topic + ".DLT"));
                        int count = 0;
                        long deadline =
                                System.nanoTime() + Duration.ofSeconds(15).toNanos();
                        while (count < 3 && System.nanoTime() < deadline)
                            for (var record : dlt.poll(Duration.ofMillis(100))) {
                                assertThat(record.value())
                                        .doesNotContain("never-copy-this", "fixtureSecret", "sellerAmount");
                                assertThat(record.headers()).isEmpty();
                                count++;
                            }
                        assertThat(count).isEqualTo(3);
                    }
                    UUID known = null;
                    for (var incident : recovery.list(0, 20, null)) {
                        UUID id = (UUID) incident.get("incidentId");
                        if (incident.get("sourceFactKey") == null) {
                            assertThat(recovery.request(UUID.randomUUID(), id, "REPLAY", "USER:1", "불명 원천 재처리 금지")
                                            .status())
                                    .isEqualTo("DENIED");
                            recovery.request(UUID.randomUUID(), id, "RESOLVE", "USER:1", "시험 poison 생산자 확인");
                        } else known = id;
                    }
                    assertThat(known).isNotNull();
                    UUID command = UUID.randomUUID();
                    assertThat(recovery.request(command, known, "REPLAY", "USER:1", "원천 검증 후 집계 재처리")
                                    .status())
                            .isEqualTo("REQUESTED");
                    var deliveries = outbox.claim(Duration.ofSeconds(30));
                    assertThat(deliveries).hasSize(1);
                    var delivery = deliveries.get(0);
                    template.send(delivery.topic(), delivery.key(), delivery.payload())
                            .get(10, TimeUnit.SECONDS);
                    outbox.published(delivery);
                    await(
                            runtime,
                            () -> "DONE".equals(recovery.command(command).get("status"))
                                    && f.catalog().generation() == 1);
                    assertThat(applied(f)).isEqualTo(100);
                    assertThat(f.jdbc()
                                    .queryForObject(
                                            "SELECT count(*) FROM p_financial_consumer_incidents WHERE status<>'RESOLVED'",
                                            Long.class))
                            .isZero();
                    assertThat(f.jdbc()
                                    .queryForObject(
                                            "SELECT count(*) FROM p_settlement_projection_errors WHERE NOT resolved",
                                            Long.class))
                            .isZero();
                    assertThat(ProjectionLoadDriver.keyDifferences(f.jdbc())).isZero();
                    assertThat(f.jdbc()
                                    .queryForObject("SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                            .isZero();
                } catch (Exception exception) {
                    throw new IllegalStateException("집계 운영 Kafka 검증 실패", exception);
                } finally {
                    factory.destroy();
                    meters.close();
                }
            });
        }
    }

    private static long applied(ProjectionConsumerBatchTest.Fixture f) {
        return f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class);
    }

    private static void await(ProjectionRuntime runtime, BooleanSupplier done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            runtime.tick();
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        assertThat(done.getAsBoolean()).as("실제 Kafka 관통 30초 제한").isTrue();
    }
}
