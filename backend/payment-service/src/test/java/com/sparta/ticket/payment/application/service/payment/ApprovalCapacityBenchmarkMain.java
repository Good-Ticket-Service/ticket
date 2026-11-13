package com.sparta.ticket.payment.application.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
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
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * #396 Kafka 승인 경로의 동시성 예산 측정. 승인 소비 설정·PG 혼합·PG 지연·DB 풀을 인자로 받고,
 * 승인 대기와 자원 지표(소비 outcome·게이지·Hikari·스레드·CPU·Consumer lag)를 1초 시계열로 모은다.
 * 실제 Kafka 수신과 PG HTTP 승인만 측정한다. 원장·집계 워커는 끈다.
 */
public final class ApprovalCapacityBenchmarkMain {
    private static final String BROKER = "127.0.0.1:19063";
    private static final String TOPIC = "payment.confirm.requested";
    private static final String GROUP = "payment-confirm-requested";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> OUTCOMES = List.of(
            "received",
            "completed",
            "local_wait",
            "db_retry",
            "capacity_pause",
            "admission_failure",
            "operation_pause",
            "invalid",
            "dlt_sent",
            "dlt_failed");
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ApprovalCapacityBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        String database = args[0];
        if (!database.matches("sk363_cap_[a-z0-9_]{1,35}")) throw new IllegalArgumentException("전용 DB가 필요하다");
        Path output = Path.of(args[1]);
        int rate = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        int tossPercent = Integer.parseInt(args[4]);
        int tossLatency = Integer.parseInt(args[5]);
        int kakaoLatency = Integer.parseInt(args[6]);
        int pool = Integer.parseInt(args[7]);
        int drainSeconds = Integer.parseInt(args[8]);
        List<String> overrides = List.of(args).subList(9, args.length);
        if (!overrides.stream()
                .allMatch(value ->
                        value.startsWith("--payment.messaging.confirm.") || value.startsWith("--pg.bulkhead.")))
            throw new IllegalArgumentException("승인 소비·PG Bulkhead 설정만 덮어쓸 수 있다");
        if (rate < 1 || rate > 1000 || count < rate || count > 100000 || tossPercent < 0 || tossPercent > 100)
            throw new IllegalArgumentException("측정 범위를 벗어났다");
        String pgUrl = "http://127.0.0.1:18077";
        var report = new LinkedHashMap<String, Object>();
        report.put("success", false);
        report.put("rate", rate);
        report.put("count", count);
        report.put("warmCount", 100);
        report.put("tossPercent", tossPercent);
        report.put("tossLatencyMillis", tossLatency);
        report.put("kakaoLatencyMillis", kakaoLatency);
        report.put("poolSize", pool);
        report.put("overrides", overrides);
        report.put("database", database);
        report.put("scope", "Kafka 승인 요청 수신부터 PG HTTP와 결과 DB/Outbox 저장까지. 발행기는 켜 두고 원장·집계 제외");
        try {
            checkGroupUnused();
            report.put("staleRecordsSkipped", skipStaleRecords());
            var options = new ArrayList<>(List.of(
                    "--spring.profiles.active=measurement",
                    "--spring.cloud.config.enabled=false",
                    "--eureka.client.enabled=false",
                    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:15463/" + database
                            + "?currentSchema=payment_schema,public",
                    "--spring.datasource.username=sk363_app",
                    "--spring.datasource.password=sk363_local_fixture",
                    "--spring.datasource.hikari.maximum-pool-size=" + pool,
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
            // Note: 측정 JVM은 config-repo를 읽지 않는다. 운영 PG 보호 설정(config-repo/payment-service.yml)을 명시한다.
            // 코드 기본값은 Bulkhead·서킷 모두 꺼짐이라, 이 값이 없으면 운영과 다른 조건이 된다(#396 15.3).
            var production = new LinkedHashMap<String, String>();
            production.put("pg.bulkhead.enabled", "true");
            production.put("pg.bulkhead.toss-max-concurrent", "200");
            production.put("pg.bulkhead.kakao-max-concurrent", "200");
            production.put("pg.bulkhead.max-wait", "50ms");
            production.put("pg.circuit-breaker.enabled", "true");
            production.put("pg.circuit-breaker.failure-rate-percent", "50");
            production.put("pg.circuit-breaker.slow-call-duration", "5s");
            production.put("pg.circuit-breaker.slow-call-rate-percent", "80");
            production.put("pg.circuit-breaker.window-size", "50");
            production.put("pg.circuit-breaker.minimum-calls", "20");
            production.put("pg.circuit-breaker.open-duration", "10s");
            production.put("pg.circuit-breaker.half-open-calls", "5");
            production.put("pg.circuit-breaker.half-open-max-wait", "30s");
            production.put("pg.connect-timeout", "2s");
            production.put("pg.read-timeout", "10s");
            for (String override : overrides) production.remove(override.substring(2, override.indexOf('=')));
            production.forEach((key, value) -> options.add("--" + key + "=" + value));
            options.addAll(overrides);
            report.put("pgProtection", production);
            var app = new SpringApplication(PaymentServiceApplication.class, ApprovalWaitProbe.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(options.toArray(String[]::new));
                    var producer = producer()) {
                var jdbc = context.getBean(JdbcTemplate.class);
                var meters = context.getBean(MeterRegistry.class);
                var clock = context.getBean(Clock.class);
                var properties = context.getBean(MessageApprovalProperties.class);
                report.put(
                        "settings",
                        Map.of(
                                "concurrency", properties.getConcurrency(),
                                "maxPollRecords", properties.getMaxPollRecords(),
                                "workers", properties.getWorkers(),
                                "perProvider", properties.getPerProvider(),
                                "maxInFlight", properties.getMaxInFlight(),
                                "retryDelayMillis", properties.getRetryDelay().toMillis()));
                var admin = new FakePgAdmin(pgUrl);
                admin.fault("TOSS", "CONFIRM", "{\"latencyMs\":" + tossLatency + "}");
                admin.fault("KAKAO", "CONFIRM", "{\"latencyMs\":" + kakaoLatency + "}");
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
                String prefix = "cap396-" + UUID.randomUUID().toString().substring(0, 8);
                long preparedAt = System.nanoTime();
                var service = context.getBean(PaymentService.class);
                var warm = prepare(service, jdbc, admin, clock, prefix + "-w", 100, tossPercent);
                var inputs = prepare(service, jdbc, admin, clock, prefix + "-m", count, tossPercent);
                report.put("fixtureSeconds", (System.nanoTime() - preparedAt) / 1e9);
                report.put("warm", run(producer, jdbc, meters, warm, 20, false, 120));
                report.put("measured", run(producer, jdbc, meters, inputs, rate, true, drainSeconds));
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
                report.put(
                        "amountSum",
                        jdbc.queryForObject(
                                "SELECT coalesce(sum(amount),0) FROM p_payments WHERE order_id LIKE ? AND status='DONE'",
                                Long.class,
                                prefix + "-m%"));
                if (!Long.valueOf(count).equals(report.get("outbox"))
                        || !Long.valueOf(count).equals(report.get("jobs")))
                    throw new IllegalStateException("승인 후 영속 인계 건수가 다르다");
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

    /**
     * 결제 준비를 8개 스레드로 나눠 만든다. 순서(인덱스)는 유지한다.
     * Note: 측정용 선점 만료는 지금 + 60분이다. 10분이면 준비가 길어진 회차(7만 건, 약 12분)에서 앞쪽 건의 선점이 끝나
     * confirmBefore가 선점 만료 + 5분을 넘고, 결제가 PAYMENT_ORDER_CONFLICT로 거부한다(#396 15.7). 예매는 선점이 유효할 때만 보낸다.
     */
    private static List<Input> prepare(
            PaymentService service,
            JdbcTemplate jdbc,
            FakePgAdmin admin,
            Clock clock,
            String prefix,
            int count,
            int tossPercent)
            throws Exception {
        long base = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L) + 1_000_000;
        var inputs = new Input[count];
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    String order = prefix + "-" + index;
                    long reservation = base + index;
                    PgProvider provider = (index % 100) < tossPercent ? PgProvider.TOSS : PgProvider.KAKAO;
                    service.checkout(new CheckoutCommand(
                            order,
                            reservation,
                            1L,
                            396L,
                            1L,
                            396L,
                            provider,
                            10000,
                            1000,
                            LocalDateTime.now(clock).plusMinutes(60),
                            LocalDateTime.now(clock).plusDays(1)));
                    String token;
                    if (provider == PgProvider.TOSS) token = admin.tossCheckout(order, 9000);
                    else {
                        String tid = jdbc.queryForObject(
                                "SELECT pg_transaction_id FROM p_payments WHERE order_id=?", String.class, order);
                        if (tid == null) throw new IllegalStateException("KAKAO 준비 거래가 없다: " + order);
                        token = admin.kakaoUserApprove(tid);
                    }
                    inputs[index] = new Input(order, reservation, provider, token, clock);
                    return null;
                }));
            }
            for (var future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        return List.of(inputs);
    }

    private static Map<String, Object> run(
            KafkaProducer<String, String> producer,
            JdbcTemplate jdbc,
            MeterRegistry meters,
            List<Input> inputs,
            int rate,
            boolean measuring,
            int drainSeconds)
            throws Exception {
        var report = new LinkedHashMap<String, Object>();
        var before = counters(meters);
        var timersBefore = timers(meters);
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime();
        if (measuring) ApprovalWaitProbe.start();
        var sampler = measuring ? WaitSampler.start(System.getProperty("cap.waitSampling", "")) : null;
        long started = System.nanoTime();
        long maxDispatchNanos = 0;
        var timeline = new ArrayList<Map<String, Object>>();
        long nextPoint = started + TimeUnit.SECONDS.toNanos(1);
        String prefix = inputs.get(0).order.substring(0, inputs.get(0).order.lastIndexOf('-'));
        for (int i = 0; i < inputs.size(); i++) {
            long due = started + i * 1_000_000_000L / rate;
            while (System.nanoTime() < due) {
                if (System.nanoTime() >= nextPoint) {
                    timeline.add(point(jdbc, meters, before, prefix, started));
                    nextPoint += TimeUnit.SECONDS.toNanos(1);
                }
                LockSupport.parkNanos(Math.min(due - System.nanoTime(), 2_000_000L));
            }
            var input = inputs.get(i);
            input.sentEpochMillis = System.currentTimeMillis();
            maxDispatchNanos = Math.max(maxDispatchNanos, System.nanoTime() - due);
            producer.send(
                    new ProducerRecord<>(TOPIC, Long.toString(input.reservation), input.message()),
                    (metadata, failure) -> {
                        if (metadata != null) input.partition = metadata.partition();
                    });
        }
        producer.flush();
        long inputFinished = System.nanoTime();
        report.put("lagAtInputEnd", lag());
        long drainLimit = inputFinished + TimeUnit.SECONDS.toNanos(drainSeconds);
        Map<String, Long> states;
        do {
            states = states(jdbc, prefix);
            if (System.nanoTime() >= nextPoint) {
                timeline.add(point(jdbc, meters, before, prefix, started));
                nextPoint += TimeUnit.SECONDS.toNanos(1);
            }
            if (states.getOrDefault("DONE", 0L) == inputs.size()
                    && counter(meters, "completed") - before.get("completed") == inputs.size()) break;
            if (counter(meters, "invalid") > before.get("invalid") || states.getOrDefault("FAILED", 0L) > 0)
                throw new IllegalStateException("유효한 승인 입력이 아니거나 최종 실패가 발생했다");
            if (System.nanoTime() > drainLimit) {
                report.put("timeline", timeline);
                report.put("states", states);
                report.put("outcomes", delta(meters, before));
                report.put("lagAtDrainEnd", lag());
                throw new IllegalStateException("승인/ACK 완료 시간 초과");
            }
            Thread.sleep(100);
        } while (true);
        if (measuring) report.put("jdbc", ApprovalWaitProbe.finish());
        if (sampler != null) report.put("waitEvents", sampler.finish());
        Map<String, Long> approved = jdbc.query(
                "SELECT order_id,approved_at FROM p_payments WHERE order_id LIKE ?",
                result -> {
                    Map<String, Long> values = new LinkedHashMap<>();
                    while (result.next())
                        values.put(
                                result.getString(1),
                                result.getTimestamp(2)
                                        .toLocalDateTime()
                                        .atZone(SEOUL)
                                        .toInstant()
                                        .toEpochMilli());
                    return values;
                },
                prefix + "-%");
        var all = new ArrayList<Long>();
        var byProvider = new LinkedHashMap<String, List<Long>>();
        var byPartition = new LinkedHashMap<Integer, Map<String, List<Long>>>();
        for (var input : inputs) {
            long latency = approved.get(input.order) - input.sentEpochMillis;
            all.add(latency);
            byProvider
                    .computeIfAbsent(input.provider.name(), key -> new ArrayList<>())
                    .add(latency);
            byPartition
                    .computeIfAbsent(input.partition, key -> new LinkedHashMap<>())
                    .computeIfAbsent(input.provider.name(), key -> new ArrayList<>())
                    .add(latency);
        }
        report.put("approvalLatencyMillis", percentiles(all));
        var providers = new LinkedHashMap<String, Object>();
        byProvider.forEach((provider, values) -> providers.put(provider, percentiles(values)));
        report.put("approvalLatencyByProviderMillis", providers);
        var partitions = new LinkedHashMap<String, Object>();
        byPartition.forEach((partition, values) -> {
            var entry = new LinkedHashMap<String, Object>();
            values.forEach((provider, latencies) -> entry.put(provider, percentiles(latencies)));
            partitions.put(String.valueOf(partition), entry);
        });
        report.put("approvalLatencyByPartitionMillis", partitions);
        report.put("inputSeconds", (inputFinished - started) / 1e9);
        report.put("drainSeconds", (System.nanoTime() - inputFinished) / 1e9);
        report.put("maxDispatchDelayMillis", maxDispatchNanos / 1e6);
        report.put("states", states);
        report.put("outcomes", delta(meters, before));
        report.put("processCpuSeconds", (os.getProcessCpuTime() - cpuBefore) / 1e9);
        report.put("threadCountEnd", ManagementFactory.getThreadMXBean().getThreadCount());
        report.put("threadCountPeak", ManagementFactory.getThreadMXBean().getPeakThreadCount());
        report.put("hikari", hikari(meters));
        report.put("pgTimers", timerDelta(timersBefore, timers(meters)));
        report.put("timeline", timeline);
        return report;
    }

    private static Map<String, Object> point(
            JdbcTemplate jdbc, MeterRegistry meters, Map<String, Double> before, String prefix, long started) {
        var point = new LinkedHashMap<String, Object>();
        point.put("second", Math.round((System.nanoTime() - started) / 1e8) / 10.0);
        point.put("done", states(jdbc, prefix).getOrDefault("DONE", 0L));
        point.put("completed", counter(meters, "completed") - before.get("completed"));
        point.put("localWait", counter(meters, "local_wait") - before.get("local_wait"));
        point.put("capacityPause", counter(meters, "capacity_pause") - before.get("capacity_pause"));
        point.put("pending", gauge(meters, "payment.confirm.pending"));
        point.put("workers", gauge(meters, "payment.confirm.workers"));
        point.put("queue", gauge(meters, "payment.confirm.queue"));
        point.put("oldestSeconds", gauge(meters, "payment.confirm.oldest.seconds"));
        point.put("hikariActive", gauge(meters, "hikaricp.connections.active"));
        point.put("hikariPending", gauge(meters, "hikaricp.connections.pending"));
        point.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        return point;
    }

    private static Map<String, Long> states(JdbcTemplate jdbc, String prefix) {
        return jdbc.query(
                "SELECT status,count(*) n FROM p_payments WHERE order_id LIKE ? GROUP BY status",
                result -> {
                    Map<String, Long> rows = new LinkedHashMap<>();
                    while (result.next()) rows.put(result.getString(1), result.getLong(2));
                    return rows;
                },
                prefix + "-%");
    }

    private static Map<String, Object> hikari(MeterRegistry meters) {
        var report = new LinkedHashMap<String, Object>();
        var acquire = meters.find("hikaricp.connections.acquire").timer();
        if (acquire != null) {
            report.put("acquireCount", acquire.count());
            report.put("acquireMeanMillis", acquire.mean(TimeUnit.MILLISECONDS));
            report.put("acquireMaxMillis", acquire.max(TimeUnit.MILLISECONDS));
        }
        var usage = meters.find("hikaricp.connections.usage").timer();
        if (usage != null) {
            report.put("usageCount", usage.count());
            report.put("usageMeanMillis", usage.mean(TimeUnit.MILLISECONDS));
            report.put("usageMaxMillis", usage.max(TimeUnit.MILLISECONDS));
        }
        var timeout = meters.find("hikaricp.connections.timeout").functionCounter();
        if (timeout != null) report.put("timeouts", timeout.count());
        report.put("max", gauge(meters, "hikaricp.connections.max"));
        return report;
    }

    /** PG HTTP 호출·Bulkhead 대기 타이머를 이름+태그별 (건수, 누적 ns)로 모은다. #396 M0 분해용. */
    private static Map<String, double[]> timers(MeterRegistry meters) {
        var values = new LinkedHashMap<String, double[]>();
        for (var timer : meters.getMeters()) {
            String name = timer.getId().getName();
            if (!(timer instanceof io.micrometer.core.instrument.Timer t)
                    || !(name.equals("payment.pg.http.duration") || name.equals("payment.pg.bulkhead.wait"))) continue;
            String key = name + timer.getId().getTags();
            values.put(key, new double[] {t.count(), t.totalTime(TimeUnit.NANOSECONDS), t.max(TimeUnit.MILLISECONDS)});
        }
        return values;
    }

    private static Map<String, Object> timerDelta(Map<String, double[]> before, Map<String, double[]> after) {
        var report = new LinkedHashMap<String, Object>();
        after.forEach((key, value) -> {
            double[] base = before.getOrDefault(key, new double[] {0, 0, 0});
            double count = value[0] - base[0];
            if (count <= 0) return;
            report.put(
                    key,
                    Map.of("count", count, "meanMillis", (value[1] - base[1]) / count / 1e6, "maxMillis", value[2]));
        });
        return report;
    }

    /**
     * #396 M6. 측정 창 동안 500ms마다 pg_stat_activity의 활성 세션을 DB·대기 종류별로 센다(관리 계정, 자기 세션 제외).
     * 대기 없음은 CPU로 센다. 표본 수 ÷ 표본 횟수 = 그 대기에 있던 평균 세션 수다.
     */
    private static final class WaitSampler extends Thread {
        private final List<String> urls;
        private final Map<String, Map<String, Long>> counts = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Long> samples = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean stop;

        private WaitSampler(List<String> urls) {
            super("cap-wait-sampler");
            setDaemon(true);
            this.urls = urls;
        }

        static WaitSampler start(String spec) {
            if (spec == null || spec.isBlank()) return null;
            var sampler = new WaitSampler(List.of(spec.split(java.util.regex.Pattern.quote("|"))));
            sampler.start();
            return sampler;
        }

        @Override
        public void run() {
            var connections = new java.util.LinkedHashMap<String, java.sql.Connection>();
            try {
                for (String url : urls)
                    connections.put(
                            url, java.sql.DriverManager.getConnection(url, "sk363_admin", "sk363_local_fixture"));
                while (!stop) {
                    for (var entry : connections.entrySet()) {
                        try (var statement = entry.getValue().createStatement();
                                var rows = statement.executeQuery("""
                                        SELECT coalesce(datname,'-'),coalesce(wait_event_type,'CPU'),coalesce(wait_event,'running'),count(*)
                                        FROM pg_stat_activity WHERE state='active' AND pid<>pg_backend_pid()
                                          AND backend_type='client backend' GROUP BY 1,2,3
                                        """)) {
                            var bucket = counts.computeIfAbsent(
                                    entry.getKey(), key -> new java.util.concurrent.ConcurrentHashMap<>());
                            while (rows.next())
                                bucket.merge(
                                        rows.getString(1).replaceAll("_[0-9a-f]{8}$", "") + "/" + rows.getString(2)
                                                + "/" + rows.getString(3),
                                        rows.getLong(4),
                                        Long::sum);
                            samples.merge(entry.getKey(), 1L, Long::sum);
                        }
                    }
                    Thread.sleep(500);
                }
            } catch (Exception exception) {
                samples.put("error:" + exception.getClass().getSimpleName(), -1L);
            } finally {
                connections.values().forEach(connection -> {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                        // Note: 측정 표본 연결 정리 실패는 결과에 영향이 없다.
                    }
                });
            }
        }

        Map<String, Object> finish() throws InterruptedException {
            stop = true;
            join(5000);
            var report = new LinkedHashMap<String, Object>();
            counts.forEach((url, bucket) -> {
                long n = samples.getOrDefault(url, 1L);
                var averages = new LinkedHashMap<String, Object>();
                bucket.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .forEach(entry ->
                                averages.put(entry.getKey(), Math.round(entry.getValue() * 100.0 / n) / 100.0));
                report.put(
                        url.replaceAll(".*:([0-9]+)/.*", "$1"),
                        Map.of("samples", n, "averageActiveSessions", averages));
            });
            samples.keySet().stream().filter(key -> key.startsWith("error:")).forEach(key -> report.put(key, true));
            return report;
        }
    }

    private static Map<String, Object> lag() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", BROKER))) {
            var committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            var request = new LinkedHashMap<TopicPartition, OffsetSpec>();
            for (int partition = 0; partition < 12; partition++)
                request.put(new TopicPartition(TOPIC, partition), OffsetSpec.latest());
            var ends = admin.listOffsets(request).all().get(5, TimeUnit.SECONDS);
            long total = 0;
            var perPartition = new LinkedHashMap<String, Long>();
            for (var entry : ends.entrySet()) {
                var offset = committed.get(entry.getKey());
                long lag = entry.getValue().offset() - (offset == null ? 0 : offset.offset());
                perPartition.put(String.valueOf(entry.getKey().partition()), lag);
                total += lag;
            }
            return Map.of("total", total, "partitions", perPartition);
        }
    }

    private static Map<String, Object> percentiles(List<Long> values) {
        if (values.isEmpty()) return Map.of("count", 0);
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

    private static Map<String, Double> counters(MeterRegistry meters) {
        var values = new LinkedHashMap<String, Double>();
        for (String outcome : OUTCOMES) values.put(outcome, counter(meters, outcome));
        return values;
    }

    private static Map<String, Double> delta(MeterRegistry meters, Map<String, Double> before) {
        var values = new LinkedHashMap<String, Double>();
        for (String outcome : OUTCOMES) values.put(outcome, counter(meters, outcome) - before.get(outcome));
        return values;
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

    /**
     * 전용 측정 브로커의 승인 그룹 offset을 토픽 끝으로 옮긴다. 이전 회차가 운영 정지로 ACK하지 못한 레코드는 다른 DB 복제본의
     * 결제라 이번 회차에서 PAYMENT_NOT_FOUND 재시도 소진으로 파티션을 멈춘다(#396 15.7). 건너뛴 건수를 결과에 남긴다.
     */
    private static long skipStaleRecords() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", BROKER))) {
            var request = new LinkedHashMap<TopicPartition, OffsetSpec>();
            for (int partition = 0; partition < 12; partition++)
                request.put(new TopicPartition(TOPIC, partition), OffsetSpec.latest());
            var ends = admin.listOffsets(request).all().get(5, TimeUnit.SECONDS);
            var committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            long skipped = 0;
            var target = new LinkedHashMap<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>();
            for (var entry : ends.entrySet()) {
                var current = committed.get(entry.getKey());
                skipped += entry.getValue().offset() - (current == null ? 0 : current.offset());
                target.put(
                        entry.getKey(),
                        new org.apache.kafka.clients.consumer.OffsetAndMetadata(
                                entry.getValue().offset()));
            }
            if (skipped > 0)
                admin.alterConsumerGroupOffsets(GROUP, target).all().get(10, TimeUnit.SECONDS);
            return skipped;
        }
    }

    private static void checkGroupUnused() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", BROKER))) {
            try {
                var groups = admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS);
                if (!groups.get(GROUP).members().isEmpty()) throw new IllegalStateException("다른 승인 Consumer가 실행 중이다");
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof GroupIdNotFoundException)) throw exception;
            }
        }
    }

    private static final class Input {
        final String order;
        final long reservation;
        final PgProvider provider;
        final String token;
        final Clock clock;
        long sentEpochMillis;
        volatile int partition = -1;

        Input(String order, long reservation, PgProvider provider, String token, Clock clock) {
            this.order = order;
            this.reservation = reservation;
            this.provider = provider;
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
