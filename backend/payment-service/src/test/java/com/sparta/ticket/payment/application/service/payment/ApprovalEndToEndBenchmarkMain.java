package com.sparta.ticket.payment.application.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.infrastructure.messaging.PaymentResultRelay;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * #384 종단 지연. Kafka 승인 요청 수신 → PG HTTP → 확정 TX → 결과 Outbox → 발행 → payment.result 수신까지
 * 한 건 단위로 구간을 잇는다. 예매 JVM은 띄우지 않고 측정 Consumer의 수신 시각을 예매 수신으로 삼는다.
 */
public final class ApprovalEndToEndBenchmarkMain {
    private static final String BROKER = "127.0.0.1:19063";
    private static final String TOPIC = "payment.confirm.requested";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ApprovalEndToEndBenchmarkMain() {}

    private record Received(long sentMillis, long receivedMillis) {}

    public static void main(String[] args) throws Exception {
        String database = args[0];
        if (!database.matches("sk363_e2e_[a-z0-9_]{1,35}")) throw new IllegalArgumentException("전용 DB가 필요하다");
        Path output = Path.of(args[1]);
        int rate = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        List<String> overrides = List.of(args).subList(4, args.length);
        if (!overrides.stream().allMatch(value -> value.startsWith("--payment.messaging.result-relay.")))
            throw new IllegalArgumentException("발행기 설정만 덮어쓸 수 있다");
        if (!List.of(10, 40).contains(rate) || count != rate * 30) throw new IllegalArgumentException("고정 측정 범위를 벗어났다");
        String pgUrl = "http://127.0.0.1:18077";
        var report = new LinkedHashMap<String, Object>();
        report.put("success", false);
        report.put("rate", rate);
        report.put("count", count);
        report.put("warmCount", 100);
        report.put("pgLatencyMillis", 300);
        report.put("database", database);
        report.put("overrides", overrides);
        report.put("scope", "Kafka 승인 요청 송신부터 PG HTTP·확정 TX·결과 Outbox·발행·payment.result 수신까지. 예매 JVM·원장·집계 제외");
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
            options.addAll(overrides);
            var app = new SpringApplication(PaymentServiceApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(options.toArray(String[]::new));
                    var producer = producer()) {
                var jdbc = context.getBean(JdbcTemplate.class);
                var meters = context.getBean(MeterRegistry.class);
                var clock = context.getBean(Clock.class);
                var admin = new FakePgAdmin(pgUrl);
                admin.fault("TOSS", "CONFIRM", "{\"latencyMs\":300}");
                report.put(
                        "relayFixedDelayMillis",
                        context.getEnvironment().getProperty("payment.messaging.result-relay.fixed-delay-ms", "100"));
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
                var received = new ConcurrentHashMap<String, Received>();
                var duplicates = new AtomicLong();
                var stop = new AtomicBoolean();
                var ready = new AtomicBoolean();
                var consumer = new Thread(() -> consume(received, duplicates, stop, ready), "e2e-result-consumer");
                consumer.start();
                long readyLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!ready.get()) {
                    if (System.nanoTime() > readyLimit) throw new IllegalStateException("측정 Consumer 준비 시간 초과");
                    Thread.sleep(50);
                }
                report.put("clockSkewBefore", skew(jdbc));
                String prefix = "e2e384-" + UUID.randomUUID().toString().substring(0, 8);
                long preparedAt = System.nanoTime();
                var warm = prepare(context.getBean(PaymentService.class), admin, clock, prefix + "-w", 100);
                var inputs = prepare(context.getBean(PaymentService.class), admin, clock, prefix + "-m", count);
                report.put("fixtureSeconds", (System.nanoTime() - preparedAt) / 1e9);
                report.put("warm", run(producer, jdbc, meters, received, warm, 20));
                report.put("measured", run(producer, jdbc, meters, received, inputs, rate));
                report.put("clockSkewAfter", skew(jdbc));
                Thread.sleep(1500);
                stop.set(true);
                consumer.join(10_000);
                report.put("duplicateDeliveries", duplicates.get());
            }
            report.put("success", true);
        } catch (Exception exception) {
            report.put("failureClass", exception.getClass().getName());
            report.put("failureMessage", String.valueOf(exception.getMessage()));
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
                    384L,
                    1L,
                    384L,
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
            Map<String, Received> received,
            List<Input> inputs,
            int rate)
            throws Exception {
        var report = new LinkedHashMap<String, Object>();
        double completedBefore = counter(meters, "completed");
        long started = System.nanoTime();
        long maxDispatchNanos = 0;
        for (int i = 0; i < inputs.size(); i++) {
            long due = started + i * 1_000_000_000L / rate;
            while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
            var input = inputs.get(i);
            input.sentEpochMillis = System.currentTimeMillis();
            maxDispatchNanos = Math.max(maxDispatchNanos, System.nanoTime() - due);
            producer.send(new ProducerRecord<>(TOPIC, Long.toString(input.reservation), input.message()));
        }
        producer.flush();
        long inputFinished = System.nanoTime();
        long drainLimit = inputFinished + TimeUnit.SECONDS.toNanos(90);
        String prefix = inputs.get(0).order.substring(0, inputs.get(0).order.lastIndexOf('-'));
        var timeline = new ArrayList<Map<String, Object>>();
        long nextPoint = started + TimeUnit.SECONDS.toNanos(1);
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
            long receivedCount = received.keySet().stream()
                    .filter(k -> k.startsWith(prefix + "-"))
                    .count();
            if (System.nanoTime() >= nextPoint) {
                nextPoint += TimeUnit.SECONDS.toNanos(1);
                var point = new LinkedHashMap<String, Object>();
                point.put("second", (System.nanoTime() - started) / 1e9);
                point.put("done", states.getOrDefault("DONE", 0L));
                point.put("received", receivedCount);
                point.put("outboxPending", gauge(meters, "payment.result.outbox.pending"));
                timeline.add(point);
            }
            if (states.getOrDefault("DONE", 0L) == inputs.size()
                    && counter(meters, "completed") - completedBefore == inputs.size()
                    && receivedCount == inputs.size()) break;
            if (states.getOrDefault("FAILED", 0L) > 0) throw new IllegalStateException("최종 실패가 발생했다");
            if (System.nanoTime() > drainLimit) throw new IllegalStateException("승인/발행/수신 완료 시간 초과");
            Thread.sleep(100);
        } while (true);
        var rows = jdbc.query(
                """
                SELECT p.order_id,p.approved_at,o.occurred_at,o.published_at,o.attempts
                FROM p_payments p JOIN p_payment_result_outbox o ON o.payment_id=p.id WHERE p.order_id LIKE ?
                """,
                result -> {
                    Map<String, long[]> values = new LinkedHashMap<>();
                    while (result.next())
                        values.put(result.getString(1), new long[] {
                            result.getTimestamp(2)
                                    .toLocalDateTime()
                                    .atZone(SEOUL)
                                    .toInstant()
                                    .toEpochMilli(),
                            result.getTimestamp(3).getTime(),
                            result.getTimestamp(4) == null
                                    ? -1
                                    : result.getTimestamp(4).getTime(),
                            result.getInt(5)
                        });
                    return values;
                },
                prefix + "-%");
        var approval = new ArrayList<Long>();
        var finalize = new ArrayList<Long>();
        var publishWait = new ArrayList<Long>();
        var delivery = new ArrayList<Long>();
        var total = new ArrayList<Long>();
        long attempts = 0;
        for (var input : inputs) {
            long[] row = rows.get(input.order);
            var arrival = received.get(input.order);
            if (row == null || arrival == null || row[2] < 0)
                throw new IllegalStateException("구간 시각이 빠졌다: " + input.order);
            approval.add(row[0] - input.sentEpochMillis);
            finalize.add(row[1] - row[0]);
            publishWait.add(arrival.sentMillis() - row[1]);
            delivery.add(arrival.receivedMillis() - arrival.sentMillis());
            total.add(arrival.receivedMillis() - input.sentEpochMillis);
            attempts += row[3];
        }
        report.put("count", inputs.size());
        report.put("inputSeconds", (inputFinished - started) / 1e9);
        report.put("drainSeconds", (System.nanoTime() - inputFinished) / 1e9);
        report.put("maxDispatchDelayMillis", maxDispatchNanos / 1e6);
        report.put("states", states);
        report.put("relayAttemptsTotal", attempts);
        report.put("sentToApprovedMillis", percentiles(approval));
        report.put("approvedToFinalizedMillis", percentiles(finalize));
        report.put("finalizedToSentMillis", percentiles(publishWait));
        report.put("sentToReceivedMillis", percentiles(delivery));
        report.put("endToEndMillis", percentiles(total));
        report.put("timeline", timeline);
        return report;
    }

    private static Map<String, Object> percentiles(List<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        var report = new LinkedHashMap<String, Object>();
        report.put("count", sorted.length);
        report.put("min", sorted[0]);
        report.put("p50", sorted[(int) Math.ceil(sorted.length * .5) - 1]);
        report.put("p95", sorted[(int) Math.ceil(sorted.length * .95) - 1]);
        report.put("p99", sorted[(int) Math.ceil(sorted.length * .99) - 1]);
        report.put("max", sorted[sorted.length - 1]);
        return report;
    }

    private static void consume(
            Map<String, Received> received, AtomicLong duplicates, AtomicBoolean stop, AtomicBoolean ready) {
        var props = new Properties();
        props.put("bootstrap.servers", BROKER);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("enable.auto.commit", "false");
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            var partitions = consumer.partitionsFor(PaymentResultRelay.TOPIC).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            ready.set(true);
            while (!stop.get()) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    long now = System.currentTimeMillis();
                    int start = record.value().indexOf("\"orderId\":\"");
                    if (start < 0) continue;
                    int end = record.value().indexOf('"', start + 11);
                    String order = record.value().substring(start + 11, end);
                    if (received.putIfAbsent(order, new Received(record.timestamp(), now)) != null)
                        duplicates.incrementAndGet();
                }
            }
        }
    }

    private static Map<String, Object> skew(JdbcTemplate jdbc) {
        long best = Long.MAX_VALUE;
        double offset = 0;
        for (int i = 0; i < 20; i++) {
            long before = System.nanoTime();
            long wallBefore = System.currentTimeMillis();
            double database = jdbc.queryForObject("SELECT extract(epoch FROM clock_timestamp())*1000", Double.class);
            long round = System.nanoTime() - before;
            if (round < best) {
                best = round;
                offset = database - (wallBefore + round / 2e6);
            }
        }
        return Map.of("databaseMinusJvmMillis", offset, "roundTripMillis", best / 1e6);
    }

    private static double counter(MeterRegistry meters, String outcome) {
        var counter =
                meters.find("payment.confirm.records").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static double gauge(MeterRegistry meters, String name) {
        var gauge = meters.find(name).gauge();
        return gauge == null ? -1 : gauge.value();
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
        long sentEpochMillis;

        Input(String order, long reservation, String token, Clock clock) {
            this.order = order;
            this.reservation = reservation;
            this.token = token;
            this.clock = clock;
        }

        String message() throws Exception {
            var occurred = OffsetDateTime.now(clock.withZone(SEOUL));
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
