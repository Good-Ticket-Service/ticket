package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.payment.SettlementJobTransactions;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 이 실행기가 만든 JVM만 부모 도구가 종료한다. 제품 코드에 장애 주입 스위치를 추가하지 않는다. */
public final class SettlementKafkaFaultProcessMain {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneId.of("Asia/Seoul"));

    private SettlementKafkaFaultProcessMain() {}

    public static void main(String[] args) throws Exception {
        String role = args[0], database = args[1], checkpoint = args[3];
        Path directory = Path.of(args[2]);
        if (!database.matches("sk363_[a-z0-9_]{1,50}")
                || !List.of("prepare", "publisher", "consumer", "projection", "rollback", "verify")
                        .contains(role)) throw new IllegalArgumentException("전용 역할/DB가 필요하다");
        var options = new ArrayList<>(List.of(
                "--spring.profiles.active=measurement",
                "--spring.cloud.config.enabled=false",
                "--eureka.client.enabled=false",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:15463/" + database
                        + "?currentSchema=payment_schema,public",
                "--spring.datasource.username=sk363_app",
                "--spring.datasource.password=sk363_local_fixture",
                "--spring.datasource.hikari.maximum-pool-size=10",
                "--spring.datasource.hikari.minimum-idle=2",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.sql.init.mode=never",
                "--spring.jpa.open-in-view=false",
                "--spring.kafka.bootstrap-servers=127.0.0.1:19063",
                "--spring.kafka.consumer.properties.session.timeout.ms=6000",
                "--spring.kafka.consumer.properties.heartbeat.interval.ms=2000",
                "--payment.settlement.posting.transport=KAFKA",
                "--payment.settlement.posting.kafka.enabled=true",
                "--payment.settlement.posting.kafka.topic=sk363." + database + ".posting.v1",
                "--payment.settlement.posting.kafka.group=sk363." + database + ".posting.v1",
                "--payment.settlement.posting.kafka.concurrency=1",
                "--payment.settlement.posting.kafka.batch-size=100",
                "--payment.settlement.posting.kafka.lease=3s",
                "--payment.settlement.posting.kafka.send-timeout=1s",
                "--payment.settlement.posting.kafka.max-publish-run=100ms",
                "--payment.settlement.posting.kafka.consumer-enabled=false",
                "--payment.settlement.posting.kafka.relay-enabled=" + "publisher".equals(role),
                "--payment.settlement.consumer-recovery.observation-enabled=false",
                "--payment.settlement.consumer-recovery.replay-enabled=false",
                "--fixture.checkpoint=" + checkpoint,
                "--fixture.directory=" + directory.toAbsolutePath()));
        for (String name : List.of(
                "payment.recovery",
                "payment.reconcile",
                "payment.recovery.refund",
                "payment.observation",
                "payment.settlement.worker",
                "payment.settlement.backfill",
                "payment.settlement.statement",
                "payment.settlement.payout",
                "payment.settlement.payout.recovery",
                "payment.settlement.reconcile",
                "payment.settlement.projection",
                "payment.pg",
                "payment.messaging.confirm",
                "payment.messaging.result-relay")) {
            if (!"projection".equals(role) || !"payment.settlement.projection".equals(name))
                options.add("--" + name + ".enabled=false");
        }
        if ("projection".equals(role))
            options.addAll(List.of(
                    "--payment.settlement.projection.enabled=true",
                    "--payment.settlement.projection.mode=B",
                    "--payment.settlement.projection.threads=1",
                    "--payment.settlement.projection.topic-prefix=sk363." + database + ".projection"));
        var app = new SpringApplication(PaymentServiceApplication.class, FaultConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(options.toArray(String[]::new))) {
            var jdbc = context.getBean(JdbcTemplate.class);
            if ("rollback".equals(role)) {
                var catalog = context.getBean(
                        com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc
                                .class);
                var db = context.getBean(
                        com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionDbWorker
                                .class);
                var build = jdbc.queryForObject(
                        "SELECT id FROM p_settlement_projection_builds WHERE mode='D' AND status='RETIRED'",
                        java.util.UUID.class);
                long generation = catalog.generation();
                catalog.control(
                        java.util.UUID.randomUUID(),
                        build,
                        "rollback",
                        generation,
                        "fixture",
                        "최신 원천 따라잡기 후 D rollback");
                catalog.fanout(build);
                while (db.process(build, 500) > 0) {}
                catalog.freeze(build);
                if (!catalog.verify(build)) throw new IllegalStateException("rollback 후보 검증 실패");
                catalog.activate(build, generation);
                save(
                        directory.resolve("rollback.json"),
                        Map.of(
                                "generation",
                                catalog.generation(),
                                "mode",
                                "D",
                                "projection",
                                SettlementKafkaBenchmarkMain.projectionOracle(jdbc, build, 110100),
                                "finance",
                                SettlementKafkaBenchmarkMain.oracle(jdbc, 100000, 100)));
                return;
            }
            if ("prepare".equals(role)) {
                var transactions = context.getBean(PaymentTransactions.class);
                for (long id = 100001; id <= 100100; id++) {
                    String key = "sk363-fault-" + id;
                    if (transactions
                            .claimApproval("sk363-ready-" + id, new ConfirmCommand(key, 10000))
                            .isEmpty()) throw new IllegalStateException("fixture 승인 실행권 없음");
                    transactions.complete(id, new PgResult.Approved(key, 10000, LocalDateTime.now(CLOCK)));
                }
            }
            if ("verify".equals(role)) {
                var report = new java.util.LinkedHashMap<String, Object>();
                report.put("oracle", SettlementKafkaBenchmarkMain.oracle(jdbc, 100000, 100));
                report.put(
                        "jobs",
                        jdbc.queryForMap(
                                "SELECT count(*) jobs,count(*) FILTER(WHERE status='DONE') done,max(publish_attempts) max_attempts,min(publish_attempts) min_attempts FROM p_settlement_jobs WHERE payment_id>100000"));
                report.put("offsets", offsets(database));
                if (args.length > 4 && "B".equals(args[4])) {
                    var build = jdbc.queryForObject(
                            "SELECT build_id FROM p_settlement_projection_head WHERE id=1", java.util.UUID.class);
                    report.put("projection", SettlementKafkaBenchmarkMain.projectionOracle(jdbc, build, 110100));
                    report.put(
                            "projectionOffsets",
                            offsetsFor(
                                    "sk363." + database + ".projection.input." + build,
                                    "sk363." + database + ".projection.processor." + build));
                }
                save(directory.resolve("verified.json"), report);
                return;
            }
            var ready = new java.util.LinkedHashMap<String, Object>();
            ready.put("pid", ProcessHandle.current().pid());
            ready.put("role", role);
            if ("consumer".equals(role)) {
                var listener = context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                        .getListenerContainer("settlement-posting");
                listener.pause();
                listener.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while ((listener.getAssignedPartitions() == null
                                || listener.getAssignedPartitions().isEmpty())
                        && System.nanoTime() < deadline) Thread.sleep(100);
                ready.put("partitions", String.valueOf(listener.getAssignedPartitions()));
                try (var admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:19063"))) {
                    ready.put(
                            "groupMembers",
                            admin.describeConsumerGroups(List.of("sk363." + database + ".posting.v1"))
                                    .all()
                                    .get(5, TimeUnit.SECONDS)
                                    .values()
                                    .iterator()
                                    .next()
                                    .members()
                                    .size());
                }
            }
            save(directory.resolve("ready.json"), ready);
            if ("prepare".equals(role)) return;
            if ("consumer".equals(role)) {
                Path barrier = directory.getParent().resolve("resume-consumers");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!Files.exists(barrier) && System.nanoTime() < deadline) Thread.sleep(100);
                if (!Files.exists(barrier)) throw new IllegalStateException("두 JVM 참여 barrier 제한 초과");
                context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                        .getListenerContainer("settlement-posting")
                        .resume();
            }
            long until = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (System.nanoTime() < until) Thread.sleep(100);
        }
    }

    private static Map<String, Object> offsets(String database) throws Exception {
        String topic = "sk363." + database + ".posting.v1";
        return offsetsFor(topic, topic);
    }

    private static Map<String, Object> offsetsFor(String topic, String group) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:19063"))) {
            var request = new java.util.HashMap<TopicPartition, OffsetSpec>();
            for (int i = 0; i < 6; i++) request.put(new TopicPartition(topic, i), OffsetSpec.latest());
            var ends = admin.listOffsets(request).all().get(5, TimeUnit.SECONDS);
            boolean caughtUp = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                var commits = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                caughtUp = ends.entrySet().stream()
                        .allMatch(e -> commits.containsKey(e.getKey())
                                && commits.get(e.getKey()).offset()
                                        >= e.getValue().offset());
                if (caughtUp) break;
                Thread.sleep(100);
            }
            if (!caughtUp) throw new IllegalStateException("종료 인수 후 offset 미수렴");
            return Map.of(
                    "caughtUp",
                    true,
                    "brokerRecords",
                    ends.values().stream().mapToLong(value -> value.offset()).sum());
        }
    }

    private static void save(Path file, Object value) throws Exception {
        Files.createDirectories(file.getParent());
        new ObjectMapper()
                .findAndRegisterModules()
                .writerWithDefaultPrettyPrinter()
                .writeValue(file.toFile(), value);
    }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        @Primary
        Clock faultClock() {
            return CLOCK;
        }

        @Bean
        static BeanPostProcessor faultCheckpoints(Environment environment) {
            String point = environment.getRequiredProperty("fixture.checkpoint");
            Path file = Path.of(environment.getRequiredProperty("fixture.directory"))
                    .resolve("checkpoint.json");
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    boolean publisher = "BROKER_ACK".equals(point) && bean instanceof SettlementPostingOutboxJdbc;
                    boolean consumer = "DB_COMMIT".equals(point) && bean instanceof SettlementJobTransactions;
                    boolean projection = "PROJECTION_COMMIT".equals(point)
                            && bean
                                    instanceof
                                    com.sparta.ticket.payment.infrastructure.persistence.settlement.projection
                                            .ProjectionConsumerJdbc;
                    if (!publisher && !consumer && !projection) return bean;
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        boolean hit = publisher
                                && "published".equals(invocation.getMethod().getName())
                                && !((List<?>) invocation.getArguments()[0]).isEmpty();
                        if (hit) park(file, point);
                        Object result = invocation.proceed();
                        if (consumer
                                && "processKafka".equals(invocation.getMethod().getName())) park(file, point);
                        if (projection
                                && "applyStrict".equals(invocation.getMethod().getName())) park(file, point);
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }

        private static void park(Path file, String point) throws Exception {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("fault hook가 금융 TX 안에 있다");
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    "{\"point\":\"" + point + "\",\"pid\":"
                            + ProcessHandle.current().pid() + ",\"transactionActive\":false}",
                    StandardOpenOption.CREATE_NEW);
            for (; ; ) Thread.sleep(1000);
        }
    }
}
