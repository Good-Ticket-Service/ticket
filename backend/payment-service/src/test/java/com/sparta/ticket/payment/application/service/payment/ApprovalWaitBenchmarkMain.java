package com.sparta.ticket.payment.application.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** #377 실제 Kafka 수신과 PG HTTP 승인만 측정한다. 후속 원장·집계 부하는 분리한다. */
public final class ApprovalWaitBenchmarkMain {
    private static final String BROKER = "127.0.0.1:19063";
    private static final String TOPIC = "payment.confirm.requested";
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ApprovalWaitBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        String database = args[0];
        if (!database.matches("sk363_cw_[a-z0-9_]{1,35}")) throw new IllegalArgumentException("전용 DB가 필요하다");
        Path output = Path.of(args[1]);
        int rate = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        if (!List.of(10, 40).contains(rate) || count != rate * 30) throw new IllegalArgumentException("고정 측정 범위를 벗어났다");
        String pgUrl = "http://127.0.0.1:18077";
        var report = new LinkedHashMap<String, Object>();
        report.put("success", false);
        report.put("rate", rate);
        report.put("count", count);
        report.put("warmCount", 100);
        report.put("scope", "Kafka 승인 요청 수신부터 실제 PG HTTP와 결과 DB/Outbox 저장까지. 원장·집계 스케줄러 제외");
        report.put("pgLatencyMillis", 300);
        report.put("database", database);
        try {
            checkGroupUnused();
            var options = new ArrayList<>(List.of(
                    "--spring.profiles.active=measurement",
                    "--spring.cloud.config.enabled=false",
                    "--eureka.client.enabled=false",
                    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:15463/" + database
                            + "?currentSchema=payment_schema,public",
                    "--spring.datasource.username=sk363_app",
                    "--spring.datasource.password=sk363_local_fixture",
                    "--spring.datasource.hikari.maximum-pool-size=20",
                    "--spring.datasource.hikari.minimum-idle=5",
                    "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.sql.init.mode=never",
                    "--spring.jpa.open-in-view=false",
                    "--spring.kafka.bootstrap-servers=" + BROKER,
                    "--payment.messaging.confirm.enabled=true",
                    "--pg.toss.base-url=" + pgUrl,
                    "--pg.kakao.base-url=" + pgUrl,
                    "--payment.settlement.posting.transport=DB",
                    "--payment.settlement.projection.mode=D",
                    "--payment.settlement.projection.auto-start=false",
                    "--logging.level.org.apache.kafka=WARN",
                    "--logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF"));
            for (String name : List.of(
                    "payment.recovery",
                    "payment.reconcile",
                    "payment.recovery.refund",
                    "payment.observation",
                    "payment.messaging.result-relay",
                    "payment.pg",
                    "payment.settlement.worker",
                    "payment.settlement.backfill",
                    "payment.settlement.statement",
                    "payment.settlement.payout",
                    "payment.settlement.payout.recovery",
                    "payment.settlement.reconcile",
                    "payment.settlement.posting.kafka",
                    "payment.settlement.consumer-recovery.replay",
                    "payment.settlement.consumer-recovery.observation")) options.add("--" + name + ".enabled=false");
            options.add("--payment.settlement.consumer-recovery.replay-enabled=false");
            options.add("--payment.settlement.consumer-recovery.observation-enabled=false");
            var app = new SpringApplication(PaymentServiceApplication.class, ApprovalWaitProbe.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(options.toArray(String[]::new));
                    var producer = producer()) {
                var jdbc = context.getBean(JdbcTemplate.class);
                var meters = context.getBean(MeterRegistry.class);
                var clock = context.getBean(Clock.class);
                var admin = new FakePgAdmin(pgUrl);
                admin.fault("TOSS", "CONFIRM", "{\"latencyMs\":300}");
                var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                long assignmentLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (registry.getListenerContainers().stream()
                                .mapToInt(container -> container.getAssignedPartitions() == null
                                        ? 0
                                        : container.getAssignedPartitions().size())
                                .sum()
                        < 12) {
                    if (System.nanoTime() > assignmentLimit) throw new IllegalStateException("파티션 할당 시간 초과");
                    Thread.sleep(100);
                }
                String prefix = "cw377-" + UUID.randomUUID().toString().substring(0, 8);
                long preparedAt = System.nanoTime();
                var warm = prepare(context.getBean(PaymentService.class), admin, clock, prefix + "-w", 100);
                var inputs = prepare(context.getBean(PaymentService.class), admin, clock, prefix + "-m", count);
                report.put("fixtureSeconds", (System.nanoTime() - preparedAt) / 1e9);
                report.put("warm", run(producer, jdbc, meters, warm, 20, false));
                report.put("measured", run(producer, jdbc, meters, inputs, rate, true));
                report.put(
                        "outbox",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM p_payment_result_outbox o JOIN p_payments p ON p.id=o.payment_id WHERE p.order_id LIKE ?",
                                Long.class,
                                prefix + "-m%"));
                report.put(
                        "jobs",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM p_settlement_jobs j JOIN p_payments p ON p.id=j.payment_id WHERE p.order_id LIKE ?",
                                Long.class,
                                prefix + "-m%"));
                if (!Long.valueOf(count).equals(report.get("outbox"))
                        || !Long.valueOf(count).equals(report.get("jobs")))
                    throw new IllegalStateException("승인 후 영속 인계 건수가 다르다");
            }
            report.put("success", true);
        } catch (Exception exception) {
            report.put("failureClass", exception.getClass().getName());
            throw exception;
        } finally {
            Files.createDirectories(output.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
    }

    private static List<Input> prepare(
            PaymentService service, FakePgAdmin admin, Clock clock, String prefix, int count) {
        var inputs = new ArrayList<Input>();
        long base = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L) + 1_000_000;
        for (int i = 0; i < count; i++) {
            String order = prefix + "-" + i;
            long reservation = base + i;
            service.checkout(new CheckoutCommand(
                    order,
                    reservation,
                    1L,
                    377L,
                    1L,
                    377L,
                    PgProvider.TOSS,
                    10000,
                    1000,
                    LocalDateTime.now(clock).plusMinutes(10),
                    LocalDateTime.now(clock).plusDays(1)));
            inputs.add(new Input(order, reservation, admin.tossCheckout(order, 9000), clock));
        }
        return inputs;
    }

    private static Map<String, Object> run(
            KafkaProducer<String, String> producer,
            JdbcTemplate jdbc,
            MeterRegistry meters,
            List<Input> inputs,
            int rate,
            boolean measuring)
            throws Exception {
        var report = new LinkedHashMap<String, Object>();
        double completedBefore = counter(meters, "completed");
        double waitsBefore = counter(meters, "local_wait");
        double retriesBefore = counter(meters, "db_retry");
        double invalidBefore = counter(meters, "invalid");
        if (measuring) ApprovalWaitProbe.start();
        long started = System.nanoTime();
        long maxDispatchNanos = 0;
        for (int i = 0; i < inputs.size(); i++) {
            long due = started + i * 1_000_000_000L / rate;
            while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
            var input = inputs.get(i);
            input.sentNanos = System.nanoTime();
            input.sentEpochMillis = System.currentTimeMillis();
            maxDispatchNanos = Math.max(maxDispatchNanos, input.sentNanos - due);
            producer.send(new ProducerRecord<>(TOPIC, Long.toString(input.reservation), input.message()));
        }
        producer.flush();
        long inputFinished = System.nanoTime();
        long drainLimit = inputFinished + TimeUnit.SECONDS.toNanos(60);
        String prefix = inputs.get(0).order.substring(0, inputs.get(0).order.lastIndexOf('-'));
        Map<String, Long> states;
        do {
            states = jdbc.query(
                    "SELECT status,count(*) n FROM p_payments WHERE order_id LIKE ? GROUP BY status",
                    result -> {
                        Map<String, Long> rows = new LinkedHashMap<>();
                        while (result.next()) rows.put(result.getString(1), result.getLong(2));
                        return rows;
                    },
                    prefix + "-%");
            if (states.getOrDefault("DONE", 0L) == inputs.size()
                    && counter(meters, "completed") - completedBefore == inputs.size()) break;
            if (counter(meters, "invalid") > invalidBefore || states.getOrDefault("FAILED", 0L) > 0)
                throw new IllegalStateException("유효한 승인 입력이 아니거나 최종 실패가 발생했다");
            if (System.nanoTime() > drainLimit) throw new IllegalStateException("승인/ACK 완료 시간 초과");
            Thread.sleep(100);
        } while (true);
        if (measuring) report.put("jdbc", ApprovalWaitProbe.finish());
        Map<String, Long> approved = jdbc.query(
                "SELECT order_id,approved_at FROM p_payments WHERE order_id LIKE ?",
                result -> {
                    Map<String, Long> values = new LinkedHashMap<>();
                    while (result.next())
                        values.put(
                                result.getString(1),
                                result.getTimestamp(2)
                                        .toLocalDateTime()
                                        .atZone(ZoneId.of("Asia/Seoul"))
                                        .toInstant()
                                        .toEpochMilli());
                    return values;
                },
                prefix + "-%");
        var latencies = inputs.stream()
                .mapToLong(input -> approved.get(input.order) - input.sentEpochMillis)
                .sorted()
                .toArray();
        report.put(
                "approvalStateLatencyMillis",
                Map.of(
                        "p50",
                        latencies[(int) Math.ceil(latencies.length * .5) - 1],
                        "p95",
                        latencies[(int) Math.ceil(latencies.length * .95) - 1],
                        "p99",
                        latencies[(int) Math.ceil(latencies.length * .99) - 1],
                        "max",
                        latencies[latencies.length - 1]));
        report.put("inputSeconds", (inputFinished - started) / 1e9);
        report.put("drainSeconds", (System.nanoTime() - inputFinished) / 1e9);
        report.put("maxDispatchDelayMillis", maxDispatchNanos / 1e6);
        report.put("states", states);
        report.put("completed", counter(meters, "completed") - completedBefore);
        report.put("localWait", counter(meters, "local_wait") - waitsBefore);
        report.put("dbRetry", counter(meters, "db_retry") - retriesBefore);
        return report;
    }

    private static double counter(MeterRegistry meters, String outcome) {
        var counter =
                meters.find("payment.confirm.records").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static KafkaProducer<String, String> producer() {
        var props = new Properties();
        props.put("bootstrap.servers", BROKER);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");
        props.put("enable.idempotence", "true");
        return new KafkaProducer<>(props);
    }

    private static void checkGroupUnused() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", BROKER))) {
            try {
                var groups = admin.describeConsumerGroups(List.of("payment-confirm-requested"))
                        .all()
                        .get(5, TimeUnit.SECONDS);
                if (!groups.get("payment-confirm-requested").members().isEmpty())
                    throw new IllegalStateException("다른 승인 Consumer가 실행 중이다");
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof GroupIdNotFoundException)) throw exception;
            }
        }
    }

    private static final class Input {
        final String order;
        final long reservation;
        final String token;
        final Clock clock;
        long sentNanos;
        long sentEpochMillis;

        Input(String order, long reservation, String token, Clock clock) {
            this.order = order;
            this.reservation = reservation;
            this.token = token;
            this.clock = clock;
        }

        String message() throws Exception {
            var occurred = OffsetDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
            return JSON.writeValueAsString(Map.of(
                    "schemaVersion",
                    1,
                    "eventId",
                    UUID.randomUUID().toString(),
                    "occurredAt",
                    occurred,
                    "orderId",
                    order,
                    "reservationId",
                    reservation,
                    "amount",
                    9000,
                    "confirmBefore",
                    occurred.plusMinutes(5),
                    "pgAuthToken",
                    token));
        }
    }
}
