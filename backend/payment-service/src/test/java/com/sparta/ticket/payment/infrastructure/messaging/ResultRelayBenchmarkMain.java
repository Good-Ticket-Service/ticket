package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sparta.ticket.payment.PaymentServiceApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * #384 결제 결과 발행기 측정. 제품의 발행기·Outbox SQL·Producer 설정을 그대로 실행하고 Outbox 행만 합성한다.
 * 승인 TX·PG 호출·예매 소비는 포함하지 않는다. 확정 시각은 제품과 같이 JVM 시계로 기록한다.
 */
public final class ResultRelayBenchmarkMain {
    private static final String BROKER = "127.0.0.1:19063";
    private static final String USER = "sk363_app";
    private static final String PASSWORD = "sk363_local_fixture";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int WARM = 200;
    private static final int INSERTERS = 4;
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String INSERT = """
            INSERT INTO p_payment_result_outbox(event_id,payment_id,order_id,reservation_id,result_status,
              occurred_at,meaning_hash,payload) VALUES(?::uuid,?,?,?,?,?,?,?)
            """;

    private ResultRelayBenchmarkMain() {}

    private record Received(long sentMillis, long receivedMillis, int partition, long offset) {}

    public static void main(String[] args) throws Exception {
        String database = args[0];
        if (!database.matches("sk363_rr_[a-z0-9_]{1,40}")) throw new IllegalArgumentException("전용 DB가 필요하다");
        Path output = Path.of(args[1]);
        String scenario = args[2];
        int first = Integer.parseInt(args[3]);
        int second = Integer.parseInt(args[4]);
        int drainCap = Integer.parseInt(args[5]);
        List<String> overrides = List.of(args).subList(6, args.length);
        if (!overrides.stream().allMatch(value -> value.startsWith("--payment.messaging.result-relay.")))
            throw new IllegalArgumentException("발행기 설정만 덮어쓸 수 있다");
        String url = "jdbc:postgresql://127.0.0.1:15463/" + database + "?currentSchema=payment_schema,public";
        var report = new LinkedHashMap<String, Object>();
        report.put("success", false);
        report.put("scenario", scenario);
        report.put("database", database);
        report.put("overrides", overrides);
        report.put("scope", "Outbox 미발행 행부터 broker ACK·완료 기록·측정 Consumer 수신까지. 승인 TX·PG·예매 소비 제외");
        try {
            var options = new ArrayList<>(List.of(
                    "--spring.profiles.active=measurement",
                    "--spring.cloud.config.enabled=false",
                    "--eureka.client.enabled=false",
                    "--spring.datasource.url=" + url,
                    "--spring.datasource.username=" + USER,
                    "--spring.datasource.password=" + PASSWORD,
                    "--spring.datasource.hikari.maximum-pool-size=20",
                    "--spring.datasource.hikari.minimum-idle=5",
                    "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.sql.init.mode=never",
                    "--spring.jpa.open-in-view=false",
                    "--spring.kafka.bootstrap-servers=" + BROKER,
                    "--payment.messaging.confirm.enabled=false",
                    "--pg.toss.base-url=http://127.0.0.1:18077",
                    "--pg.kakao.base-url=http://127.0.0.1:18077",
                    "--payment.settlement.posting.transport=DB",
                    "--payment.settlement.projection.mode=D",
                    "--payment.settlement.projection.auto-start=false",
                    "--logging.level.org.apache.kafka=WARN"));
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
            long bootStarted = System.nanoTime();
            try (var context = app.run(options.toArray(String[]::new))) {
                report.put("bootSeconds", (System.nanoTime() - bootStarted) / 1e9);
                var jdbc = context.getBean(JdbcTemplate.class);
                var meters = context.getBean(MeterRegistry.class);
                var config = context.getBean(PaymentResultRelayProperties.class);
                report.put(
                        "relay",
                        Map.of(
                                "batchSize", config.batchSize(),
                                "leaseMillis", config.lease().toMillis(),
                                "sendTimeoutMillis", config.sendTimeout().toMillis(),
                                "maxRunMillis", config.maxRun().toMillis(),
                                "retryDelayMillis", config.retryDelay().toMillis(),
                                "maxRoundsPerTick", config.maxRoundsPerTick(),
                                "fixedDelayMillis",
                                        context.getEnvironment()
                                                .getProperty("payment.messaging.result-relay.fixed-delay-ms", "100")));
                long existing = jdbc.queryForObject("SELECT count(*) FROM p_payment_result_outbox", Long.class);
                long unpublished = jdbc.queryForObject(
                        "SELECT count(*) FROM p_payment_result_outbox WHERE published_at IS NULL", Long.class);
                if (unpublished != 0) throw new IllegalStateException("시작 전 미발행 행이 있다");
                report.put("existingOutboxRows", existing);
                report.put("clockSkewBefore", skew(jdbc));
                var received = new ConcurrentHashMap<String, Received>();
                var duplicates = new AtomicLong();
                var stop = new AtomicBoolean();
                var ready = new AtomicBoolean();
                var consumer = new Thread(() -> consume(received, duplicates, stop, ready), "relay-bench-consumer");
                consumer.start();
                long readyLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!ready.get()) {
                    if (System.nanoTime() > readyLimit) throw new IllegalStateException("측정 Consumer 준비 시간 초과");
                    Thread.sleep(50);
                }
                String run = UUID.randomUUID().toString().substring(0, 8);
                long base = 9_000_000_000_000L
                        + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000L) * 1_000_000L;
                report.put("run", run);
                // 예열은 Producer 연결·JIT·SQL 계획을 준비한다. 측정 창과 통계 차이에서 제외한다.
                var warm = new Feed(url, "rr384-" + run + "-w", base, 50, WARM);
                warm.start();
                warm.join();
                await(jdbc, 60);
                var before = stats(jdbc);
                double publishedBefore = counter(meters, "payment.result.outbox.published");
                double ackedBefore = counter(meters, "payment.result.broker.acknowledged");
                double failuresBefore = counter(meters, "payment.result.delivery.failures");
                String prefix = "rr384-" + run + "-m";
                var timeline = new ArrayList<Map<String, Object>>();
                long inserted;
                long windowSeconds;
                long started = System.nanoTime();
                long startedMillis = System.currentTimeMillis();
                Feed feed = null;
                if (scenario.equals("drain")) {
                    // first=사전 적재 건수, second=측정 창(초). 0이면 모두 비울 때까지 본다.
                    inserted = preload(jdbc, prefix, base + 100_000_000L, first);
                    started = System.nanoTime();
                    startedMillis = System.currentTimeMillis();
                    windowSeconds = second;
                } else if (scenario.equals("rate")) {
                    // first=초당 유입, second=유입 시간(초)
                    feed = new Feed(url, prefix, base + 100_000_000L, first, first * second);
                    feed.start();
                    inserted = (long) first * second;
                    windowSeconds = second;
                } else throw new IllegalArgumentException("scenario는 drain 또는 rate다");
                long limitSeconds = windowSeconds == 0 ? drainCap : windowSeconds + drainCap;
                Double windowPublished = null;
                Double drainedAt = null;
                for (int tick = 1; tick <= limitSeconds; tick++) {
                    long due = started + TimeUnit.SECONDS.toNanos(tick);
                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                    double published = counter(meters, "payment.result.outbox.published") - publishedBefore;
                    var point = new LinkedHashMap<String, Object>();
                    point.put("second", tick);
                    point.put("inserted", feed == null ? inserted : feed.done.get());
                    point.put("published", published);
                    point.put("acknowledged", counter(meters, "payment.result.broker.acknowledged") - ackedBefore);
                    point.put("pendingGauge", gauge(meters, "payment.result.outbox.pending"));
                    point.put("oldestSecondsGauge", gauge(meters, "payment.result.outbox.oldest.seconds"));
                    point.put("received", received.size());
                    timeline.add(point);
                    if (windowSeconds != 0 && tick == windowSeconds) windowPublished = published;
                    boolean inputDone = feed == null || !feed.isAlive();
                    if (inputDone && published >= inserted) {
                        drainedAt = (System.nanoTime() - started) / 1e9;
                        break;
                    }
                }
                if (feed != null) {
                    feed.join(TimeUnit.SECONDS.toMillis(30));
                    if (feed.isAlive() || feed.failure != null)
                        throw new IllegalStateException("유입 생성 실패", feed.failure);
                    report.put("feedMaxLagMillis", feed.maxLagNanos.get() / 1e6);
                    report.put("feedSeconds", feed.elapsedNanos / 1e9);
                }
                double elapsed = (System.nanoTime() - started) / 1e9;
                var after = stats(jdbc);
                double published = counter(meters, "payment.result.outbox.published") - publishedBefore;
                report.put("inserted", inserted);
                report.put("published", published);
                report.put("acknowledged", counter(meters, "payment.result.broker.acknowledged") - ackedBefore);
                report.put("deliveryFailures", counter(meters, "payment.result.delivery.failures") - failuresBefore);
                report.put("observedSeconds", elapsed);
                report.put("drainedAtSeconds", drainedAt);
                if (windowPublished != null) {
                    report.put("windowSeconds", windowSeconds);
                    report.put("windowPublished", windowPublished);
                    report.put("windowPublishPerSecond", windowPublished / windowSeconds);
                } else report.put("publishPerSecond", published / elapsed);
                report.put("timeline", timeline);
                report.put("statsBefore", before);
                report.put("statsAfter", after);
                report.put("producer", producerMetrics(context.getBean(KafkaTemplate.class)));
                report.put("clockSkewAfter", skew(jdbc));
                Thread.sleep(1500);
                stop.set(true);
                consumer.join(10_000);
                report.put("duplicateDeliveries", duplicates.get());
                report.putAll(rows(jdbc, prefix, received, startedMillis, first, scenario.equals("rate")));
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

    /** 열린 모델 유입. 예정 시각에 한 건씩 autocommit INSERT한다. 발행기 풀과 섞이지 않도록 별도 연결을 쓴다. */
    private static final class Feed extends Thread {
        final String url;
        final String prefix;
        final long base;
        final int rate;
        final int count;
        final AtomicLong done = new AtomicLong();
        final AtomicLong maxLagNanos = new AtomicLong();
        volatile Exception failure;
        volatile long elapsedNanos;

        Feed(String url, String prefix, long base, int rate, int count) {
            super("relay-bench-feed");
            this.url = url;
            this.prefix = prefix;
            this.base = base;
            this.rate = rate;
            this.count = count;
        }

        @Override
        public void run() {
            long started = System.nanoTime();
            var workers = new ArrayList<Thread>();
            for (int worker = 0; worker < INSERTERS; worker++) {
                int offset = worker;
                var thread = new Thread(
                        () -> {
                            try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
                                    var statement = connection.prepareStatement(INSERT)) {
                                for (int slot = offset; slot < count; slot += INSERTERS) {
                                    long due = started + slot * 1_000_000_000L / rate;
                                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                                    maxLagNanos.accumulateAndGet(System.nanoTime() - due, Math::max);
                                    bind(statement, prefix, base, slot, rate);
                                    statement.executeUpdate();
                                    done.incrementAndGet();
                                }
                            } catch (Exception exception) {
                                failure = exception;
                            }
                        },
                        "relay-bench-insert-" + worker);
                workers.add(thread);
                thread.start();
            }
            try {
                for (Thread worker : workers) worker.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = exception;
            }
            elapsedNanos = System.nanoTime() - started;
        }
    }

    /** 10건 중 1건은 같은 예약의 FAILED이고, 약 1초 뒤 자리의 DONE이 짝이다. 순서 역전 확인용이다. */
    private static long pairGap(int rate) {
        return 5 + 10L * ((rate + 9) / 10);
    }

    private static void bind(java.sql.PreparedStatement statement, String prefix, long base, int slot, int rate)
            throws Exception {
        boolean failed = slot % 10 == 0;
        long partner = slot - pairGap(rate);
        long reservation = slot % 10 == 5 && partner >= 0 ? base + partner : base + slot;
        var occurred = OffsetDateTime.now(SEOUL);
        var message = new PaymentResultMessage(
                UUID.randomUUID().toString(),
                1,
                occurred,
                prefix + "-" + slot,
                reservation,
                base + slot,
                failed ? "FAILED" : "DONE",
                9000,
                null,
                failed ? "REJECT_CARD_COMPANY" : null,
                failed ? null : occurred);
        statement.setString(1, message.eventId());
        statement.setLong(2, message.paymentId());
        statement.setString(3, message.orderId());
        statement.setLong(4, message.reservationId());
        statement.setString(5, message.status());
        statement.setObject(6, occurred);
        statement.setString(7, "0".repeat(64));
        statement.setString(8, JSON.writeValueAsString(message));
    }

    /** 사전 적재는 한 TX로 넣어 모든 행이 같은 시각에 발행 가능해진다. 순서 짝은 만들지 않는다. */
    private static long preload(JdbcTemplate jdbc, String prefix, long base, int count) {
        var occurred = OffsetDateTime.now(SEOUL);
        return jdbc.update(
                """
                INSERT INTO p_payment_result_outbox(event_id,payment_id,order_id,reservation_id,result_status,
                  occurred_at,meaning_hash,payload)
                SELECT e,?+i,?||'-'||i,?+i,'DONE',?,repeat('0',64),format(
                  '{"eventId":"%s","schemaVersion":1,"occurredAt":"%s","orderId":"%s","reservationId":%s,"paymentId":%s,"status":"DONE","amount":9000,"failureType":null,"failureCode":null,"approvedAt":"%s"}',
                  e,?::text,?||'-'||i,?+i,?+i,?::text)
                FROM (SELECT i,gen_random_uuid() e FROM generate_series(0,?-1) i) s
                """, base, prefix, base, occurred, occurred.toString(), prefix, base, base, occurred.toString(), count);
    }

    private static void await(JdbcTemplate jdbc, int seconds) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (jdbc.queryForObject(
                        "SELECT count(*) FROM p_payment_result_outbox WHERE published_at IS NULL", Long.class)
                != 0) {
            if (System.nanoTime() > limit) throw new IllegalStateException("예열 발행 시간 초과");
            Thread.sleep(200);
        }
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
                    int start = record.value().indexOf("\"eventId\":\"");
                    if (start < 0) continue;
                    String event = record.value().substring(start + 11, start + 47);
                    if (received.putIfAbsent(
                                    event, new Received(record.timestamp(), now, record.partition(), record.offset()))
                            != null) duplicates.incrementAndGet();
                }
            }
        }
    }

    /** 한 건 단위 구간과 순서 짝을 계산한다. published_at은 DB 시계라 clockSkew와 함께 해석한다. */
    private static Map<String, Object> rows(
            JdbcTemplate jdbc,
            String prefix,
            Map<String, Received> received,
            long startedMillis,
            int rate,
            boolean pairs) {
        var report = new LinkedHashMap<String, Object>();
        var wait = new ArrayList<Long>();
        var record = new ArrayList<Long>();
        var deliver = new ArrayList<Long>();
        var slots = new LinkedHashMap<Integer, Received>();
        long[] totals = new long[5];
        jdbc.query(
                """
                SELECT event_id::text,order_id,occurred_at,published_at,attempts,last_error
                FROM p_payment_result_outbox WHERE order_id LIKE ?
                """,
                result -> {
                    totals[0]++;
                    totals[1] += result.getInt(5);
                    totals[2] = Math.max(totals[2], result.getInt(5));
                    if ("RUN_BUDGET".equals(result.getString(6))) totals[3]++;
                    Timestamp publishedAt = result.getTimestamp(4);
                    if (publishedAt == null) return;
                    totals[4]++;
                    var arrival = received.get(result.getString(1));
                    if (arrival == null) return;
                    long occurred = result.getTimestamp(3).getTime();
                    wait.add(arrival.sentMillis() - occurred);
                    record.add(publishedAt.getTime() - arrival.sentMillis());
                    deliver.add(arrival.receivedMillis() - arrival.sentMillis());
                    String order = result.getString(2);
                    slots.put(Integer.parseInt(order.substring(order.lastIndexOf('-') + 1)), arrival);
                },
                prefix + "-%");
        report.put("rows", totals[0]);
        report.put("rowsPublished", totals[4]);
        report.put("rowsReceived", wait.size());
        report.put("attemptsTotal", totals[1]);
        report.put("attemptsMax", totals[2]);
        report.put("lastErrorRunBudget", totals[3]);
        report.put("publishWaitMillis", percentiles(wait));
        report.put("sendToRecordedMillis", percentiles(record));
        report.put("sendToReceivedMillis", percentiles(deliver));
        if (pairs) {
            long both = 0;
            long inverted = 0;
            for (var entry : slots.entrySet()) {
                if (entry.getKey() % 10 != 0) continue;
                var done = slots.get((int) (entry.getKey() + pairGap(rate)));
                if (done == null) continue;
                both++;
                if (done.partition() != entry.getValue().partition())
                    throw new IllegalStateException("같은 예약이 다른 파티션에 있다");
                if (done.offset() < entry.getValue().offset()) inverted++;
            }
            report.put("pairsDelivered", both);
            report.put("pairsInverted", inverted);
        }
        report.put("measuredFromEpochMillis", startedMillis);
        return report;
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

    /** 왕복 중간 시각으로 DB 시계와 JVM 시계의 차이를 잰다. 양수면 DB가 앞선다. */
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

    /** 통계는 초기화하지 않고 전후 차이로 본다. 이 DB에서 이 계정이 실행한 Outbox 문장만 모은다. */
    private static Map<String, Object> stats(JdbcTemplate jdbc) {
        var report = new LinkedHashMap<String, Object>();
        report.put("capturedAt", Instant.now().toString());
        report.put("statements", jdbc.queryForList("""
                        SELECT queryid::text id,left(regexp_replace(query,'\\s+',' ','g'),90) query,calls,
                          total_exec_time,rows,wal_records,wal_bytes::bigint wal_bytes,shared_blks_hit,
                          shared_blks_read,shared_blks_dirtied
                        FROM public.pg_stat_statements
                        WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
                          AND query ILIKE '%p_payment_result_outbox%' AND query NOT ILIKE '%pg_stat_statements%'
                        """));
        report.put("table", jdbc.queryForMap("""
                        SELECT n_tup_ins,n_tup_upd,n_tup_hot_upd,n_tup_del,n_live_tup,n_dead_tup,
                          pg_relation_size(relid) heap_bytes,pg_indexes_size(relid) index_bytes,
                          pg_relation_size('ix_payment_result_outbox_pending') pending_index_bytes
                        FROM pg_stat_user_tables WHERE relname='p_payment_result_outbox'
                        """));
        report.put(
                "database",
                jdbc.queryForMap(
                        "SELECT xact_commit,xact_rollback,blks_read,blks_hit FROM pg_stat_database WHERE datname=current_database()"));
        return report;
    }

    private static Map<String, Object> producerMetrics(KafkaTemplate<?, ?> template) {
        var report = new LinkedHashMap<String, Object>();
        template.metrics().forEach((name, metric) -> {
            if (name.group().equals("producer-metrics")
                    && List.of(
                                    "batch-size-avg",
                                    "records-per-request-avg",
                                    "record-queue-time-avg",
                                    "request-latency-avg",
                                    "request-latency-max",
                                    "record-send-total",
                                    "record-error-total",
                                    "record-retry-total",
                                    "request-total")
                            .contains(name.name())) report.put(name.name(), metric.metricValue());
        });
        return report;
    }

    private static double counter(MeterRegistry meters, String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private static double gauge(MeterRegistry meters, String name) {
        var gauge = meters.find(name).gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
