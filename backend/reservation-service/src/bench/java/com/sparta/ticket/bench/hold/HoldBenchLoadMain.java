package com.sparta.ticket.bench.hold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #434 좌석 선점 부하 생성기. #390 통합시험 대조군과 같은 모양의 부하를 예매 서비스에 바로 보낸다(Gateway 없음).
 *
 * - 다른 좌석 선점(base): 초당 {@link #RATE}건을 고르게 보낸다. 매번 다른 좌석·다른 사용자이고, 성공하면 {@link #HOLD_MS} 뒤 해제한다.
 * - 경합 없는 burst: 매초 정각에 {@link #BURST}명이 각자 다른 좌석을 동시에 선점하고, 성공하면 같은 시간 뒤 해제한다.
 * - 좌석 상태 조회: 시작 전 한 번과 측정 동안 1초마다 한 번. 화면을 보는 사용자 몫이고, 좌석 배치도 스냅샷을 캐시에 유지한다(#447).
 * 처음 {@link #WARMUP_SECONDS}초는 예열이고, 그 뒤 {@link #MEASURE_SECONDS}초 동안 보낸 요청만 집계한다.
 *
 * 부하와 함께 서버 CPU(0.2초, /proc), 예매 서비스 Hikari·Tomcat·GC(1초, /actuator/prometheus), DB 연결 수(1초), DB CPU(1초, 컨테이너
 * cgroup)를 표본으로 남긴다. 측정 회차마다 새 회차 좌석을 만들어 앞 회차의 예약과 섞이지 않게 한다.
 *
 * 인자: baseUrl jdbcUrl jdbcUser jdbcPassword output pool rep serverPid pgContainer
 */
public final class HoldBenchLoadMain {

    // 한도 확인 실행에서만 -Dbench.rate·-Dbench.burst로 바꾼다. 기본은 #390 대조군과 같은 250·250
    static final int RATE = Integer.getInteger("bench.rate", 250);
    static final int BURST = Integer.getInteger("bench.burst", 250);
    // 모의 매진(#447)은 -Dbench.seats=25000으로 키운다
    static final int SEATS = Integer.getInteger("bench.seats", 2000);
    /** mix: 다른 좌석 고른 부하 + 경합 없는 burst(#434). sellout: 모의 매진(#447). */
    static final String MODE = System.getProperty("bench.mode", "mix");
    /** 모의 매진에서 안에 동시에 있는 사용자 수(대기열 max-active에 해당). */
    static final int ACTIVE = Integer.getInteger("bench.active", 1000);
    /** 모의 매진 상한 시간. 이 안에 매진되지 않으면 그대로 끝낸다. */
    static final int SELLOUT_LIMIT_SECONDS = Integer.getInteger("bench.sellout.seconds", 180);
    /** 모의 매진에서 처음 좌석을 고르는 시간(ms, 무작위 범위). 미리 구역을 정해 두고 빠르게 누르는 사용자를 가정한다. */
    static final int CHOOSE_MIN_MS = Integer.getInteger("bench.choose.min", 1_000);

    static final int CHOOSE_MAX_MS = Integer.getInteger("bench.choose.max", 2_000);

    static final int USERS_PER_LOAD = 1250;
    static final long HOLD_MS = 500;
    // 짧은 확인 실행에서만 -Dbench.warmup·-Dbench.measure로 줄인다(스크립트의 LOAD_JAVA_OPTS)
    static final int WARMUP_SECONDS = Integer.getInteger("bench.warmup", 20);
    static final int MEASURE_SECONDS = Integer.getInteger("bench.measure", 60);
    static final double CPU_SATURATED = 0.9;
    static final int SERVER_CPUS = 2;
    static final long LATE_MS = 100;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern METRIC = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*})?\\s+(\\S+)");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newFixedThreadPool(16))
            .build();
    private final String baseUrl;
    private final Queue<Sample> samples = new ConcurrentLinkedQueue<>();
    private final Queue<Map<String, Object>> serverSamples = new ConcurrentLinkedQueue<>();
    private final Queue<double[]> cpuSamples = new ConcurrentLinkedQueue<>();
    // 부하 생성기 자신의 CPU. 생성기가 병목이면 서버 결과를 믿을 수 없다
    private final Queue<double[]> loadCpuSamples = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    // 멈춤 진단(#434): 처리 중인 요청의 시작 시각. 가장 오래된 요청이 STALL_MS를 넘으면 서버 스레드 덤프를 남긴다
    private final Map<Long, Long> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong requestSeq = new AtomicLong();
    private final List<String> dumps = new ArrayList<>();
    static final long STALL_MS = 500;
    static final int MAX_DUMPS = 5;

    private HoldBenchLoadMain(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 요청 한 건. intended는 보낼 예정이던 시각(t0 기준 ns), late는 실제로 보낸 시각과의 차이(ms). */
    record Sample(String load, String kind, long intendedNanos, double latencyMs, double lateMs, String outcome) {}

    public static void main(String[] args) throws Exception {
        String baseUrl = args[0];
        String jdbcUrl = args[1];
        String jdbcUser = args[2];
        String jdbcPassword = args[3];
        Path output = Path.of(args[4]);
        int pool = Integer.parseInt(args[5]);
        int rep = Integer.parseInt(args[6]);
        long serverPid = Long.parseLong(args[7]);
        String pgContainer = args[8];

        HoldBenchLoadMain load = new HoldBenchLoadMain(baseUrl);
        // 같은 서버에서 부하 단계를 번갈아 잴 때 회차·사용자가 겹치지 않게 실행 번호를 받는다(-Dbench.run, 스크립트가 넘김)
        long run = Long.getLong("bench.run", pool * 100L + rep);
        long scheduleId = 900_000L + run;
        List<Long> seats;
        try (Connection db = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            seats = seed(db, scheduleId, "sellout".equals(MODE) ? SECTION_SEATS : 0);
        }
        List<Long> burstSeats = seats.subList(0, BURST);
        List<Long> baseSeats = seats.subList(BURST, seats.size());
        // 사용자는 회차마다 다른 범위를 쓴다. 같은 회차 안에서는 burst와 다른 좌석 선점이 사용자를 나눠 쓴다
        long userBase = run * 1_000_000L;
        if ("sellout".equals(MODE)) {
            load.runSellout(
                    output,
                    pool,
                    rep,
                    scheduleId,
                    seats,
                    userBase,
                    serverPid,
                    jdbcUrl,
                    jdbcUser,
                    jdbcPassword,
                    pgContainer);
            System.exit(0);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pool", pool);
        report.put("rep", rep);
        report.put("schedule_id", scheduleId);
        report.put(
                "workload",
                Map.of(
                        "base_rate",
                        RATE,
                        "burst",
                        BURST,
                        "seats",
                        SEATS,
                        "hold_ms",
                        HOLD_MS,
                        "warmup_seconds",
                        WARMUP_SECONDS,
                        "measure_seconds",
                        MEASURE_SECONDS,
                        "sale_validation_delay_ms",
                        HoldBenchServerMain.SALE_VALIDATION_DELAY.toMillis()));

        long t0 = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        int totalSeconds = WARMUP_SECONDS + MEASURE_SECONDS;
        // 좌석 화면을 보는 사용자 몫으로 좌석 상태를 미리 한 번 받고, 측정 동안 1초마다 받는다(#447). 실제로는 화면을 본 뒤 선점하므로
        // 좌석 배치도 스냅샷이 캐시에 있다. 조회가 없으면 스냅샷이 만들어지지 않아 선점이 늘 DB에서 처음 읽기를 한다
        load.readStatus(scheduleId, userBase + 1);
        String prefix = output.toString().replaceFirst("\\.json$", "");
        Thread sampler = new Thread(
                () -> load.sampleServer(t0, serverPid, jdbcUrl, jdbcUser, jdbcPassword, pgContainer, prefix));
        Thread viewer = new Thread(() -> {
            while (load.running.get()) {
                load.readStatus(scheduleId, userBase + 1);
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
            }
        });
        viewer.setDaemon(true);
        viewer.start();
        sampler.setDaemon(true);
        sampler.start();

        ExecutorService burst = Executors.newFixedThreadPool(BURST);
        for (int slot = 0; slot < BURST; slot++) {
            int s = slot;
            burst.execute(() -> {
                for (int k = 0; k < totalSeconds; k++) {
                    long intended = TimeUnit.SECONDS.toNanos(k);
                    long userId = userBase + 1 + (k * (long) BURST + s) % USERS_PER_LOAD;
                    load.attempt("burst", t0, intended, userId, scheduleId, burstSeats.get(s));
                }
            });
        }
        // 다른 좌석 선점: 보낼 시각마다 일감을 넘긴다. 일꾼이 모자라면 줄이 길어지고 그만큼 late로 남는다
        ExecutorService base = Executors.newFixedThreadPool(1000);
        long interval = TimeUnit.SECONDS.toNanos(1) / RATE;
        long baseCount = (long) RATE * totalSeconds;
        for (long j = 0; j < baseCount; j++) {
            long intended = j * interval;
            LockSupport.parkNanos(t0 + intended - System.nanoTime());
            long userId = userBase + 5_000 + 1 + j % USERS_PER_LOAD;
            Long seat = baseSeats.get((int) (j % baseSeats.size()));
            base.execute(() -> load.attempt("base", t0, intended, userId, scheduleId, seat));
        }
        burst.shutdown();
        base.shutdown();
        boolean finished = burst.awaitTermination(2, TimeUnit.MINUTES) & base.awaitTermination(2, TimeUnit.MINUTES);
        load.running.set(false);
        sampler.join(5_000);

        long measureFrom = TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        long measureTo = TimeUnit.SECONDS.toNanos(totalSeconds);
        report.put("finished", finished);
        report.put("base", load.summarize("base", measureFrom, measureTo, RATE * MEASURE_SECONDS));
        report.put("burst", load.summarize("burst", measureFrom, measureTo, BURST * MEASURE_SECONDS));
        report.put("server", load.summarizeServer(t0, measureFrom, measureTo));
        report.put("per_second", load.perSecond(totalSeconds));
        report.put("thread_dumps", load.dumps);
        // 처음 좌석 읽기 경로의 회차 끝 누적값(서버를 띄운 뒤의 합). 회차 사이 차이로 이 회차 몫을 본다
        load.serverSamples.stream()
                .reduce((first, second) -> second)
                .ifPresent(last -> report.put(
                        "first_read",
                        Map.of(
                                "snapshot", last.getOrDefault("first_read_snapshot", 0.0),
                                "db", last.getOrDefault("first_read_db", 0.0))));
        // 1초 서버 표본 원본. 멈춘 초의 서버 쪽 처리 시간·연결 대기를 타임라인과 맞춰 본다
        report.put("server_rows", new ArrayList<>(load.serverSamples));
        Files.createDirectories(output.getParent());
        JSON.writeValue(output.toFile(), report);
        System.out.println("[bench] wrote " + output);
        System.exit(0);
    }

    /** 새 회차 좌석을 만들고 id를 좌석 번호 순으로 돌려준다. 컬럼 값은 seat.sql 제약을 따른다. */
    static List<Long> seed(Connection db, long scheduleId, int sectionSeats) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,"
                        + " section, row_label, seat_number, is_sellable, status, config_version,"
                        + " created_at, created_by, updated_at, updated_by)"
                        + " SELECT ?, g, 7, 'VIP', 150000,"
                        + " CASE WHEN ? > 0 THEN 'S' || lpad(((g - 1) / ? + 1)::text, 2, '0') ELSE 'A' END,"
                        + " 'R' || ((g - 1) / 25 + 1), (g - 1) % 25 + 1, true,"
                        + " 'AVAILABLE', 1, now(), 'bench', now(), 'bench' FROM generate_series(1, ?) g")) {
            insert.setLong(1, scheduleId);
            // 구역: sectionSeats석씩 S01, S02, …(모의 매진). 0이면 한 구역 A(다른 좌석·burst 부하)
            insert.setInt(2, sectionSeats);
            insert.setInt(3, Math.max(sectionSeats, 1));
            insert.setInt(4, SEATS);
            insert.executeUpdate();
        }
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement select =
                db.prepareStatement("SELECT id FROM p_schedule_seats WHERE schedule_id = ? ORDER BY venue_seat_id")) {
            select.setLong(1, scheduleId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
        }
        if (ids.size() != SEATS) {
            throw new IllegalStateException("좌석 준비 실패: " + ids.size());
        }
        return ids;
    }

    /** 정해진 시각까지 기다렸다가 선점하고, 성공하면 HOLD_MS 뒤 해제한다. */
    void attempt(String load, long t0, long intended, long userId, long scheduleId, long seatId) {
        long wait = t0 + intended - System.nanoTime();
        if (wait > 0) {
            LockSupport.parkNanos(wait);
        }
        long sent = System.nanoTime();
        double lateMs = (sent - t0 - intended) / 1e6;
        long holdId = requestSeq.incrementAndGet();
        inFlight.put(holdId, sent);
        String path = "/api/seats/schedules/" + scheduleId + "/holds";
        HttpRequest hold = request(path, userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scheduleSeatId\":" + seatId + "}"))
                .build();
        String outcome;
        JsonNode body = null;
        try {
            HttpResponse<String> response = http.send(hold, HttpResponse.BodyHandlers.ofString());
            body = parse(response.body());
            outcome = classify(response.statusCode(), body);
        } catch (IOException e) {
            outcome = "error";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            inFlight.remove(holdId);
        }
        samples.add(new Sample(load, "hold", intended, (System.nanoTime() - sent) / 1e6, lateMs, outcome));
        if (!"created".equals(outcome)) {
            return;
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(HOLD_MS));
        long reservationId = body.path("data").path("reservationId").asLong();
        long releaseSent = System.nanoTime();
        String releaseOutcome;
        try {
            HttpResponse<String> response = http.send(
                    request(path + "/" + reservationId, userId).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            releaseOutcome = response.statusCode() == 204 ? "released" : "release_failed";
        } catch (IOException e) {
            releaseOutcome = "release_failed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        samples.add(new Sample(load, "release", intended, (System.nanoTime() - releaseSent) / 1e6, 0, releaseOutcome));
    }

    /** 좌석 상태 조회 한 번. 실패해도 선점 측정은 이어 간다. */
    void readStatus(long scheduleId, long userId) {
        try {
            http.send(
                    request("/api/seats/schedules/" + scheduleId + "/status", userId)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            System.err.println("[bench] status read failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Gateway가 인증 뒤 넣는 사용자 헤더를 직접 넣는다(common-core UserHeaders). */
    private HttpRequest.Builder request(String path, long userId) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(65))
                .header("X-User-Id", Long.toString(userId))
                .header("X-Username", "bench" + userId)
                .header("X-User-Role", "USER");
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    /** 통합시험 scenario5.py의 classify와 같은 규칙이다. */
    static String classify(int status, JsonNode body) {
        String code = body.path("code").asText("");
        if (status == 201) return "created";
        if (status == 200) return "existing";
        if (status == 409 && "SEAT_ALREADY_TAKEN".equals(code)) return "taken";
        if (status == 409 && "PURCHASE_LIMIT_EXCEEDED".equals(code)) return "purchase_limit";
        return "error";
    }

    /**
     * 0.2초마다 서버 프로세스 CPU·처리 중인 요청·DB 세션 대기 종류를 타임라인(prefix-timeline.jsonl)에, 1초마다 서버 지표·DB 연결 수·
     * DB CPU를 남긴다. 처리 중인 요청이 STALL_MS를 넘으면 서버 스레드 덤프(prefix-dump-초.txt)를 남긴다.
     */
    void sampleServer(
            long t0, long serverPid, String jdbcUrl, String user, String password, String pgContainer, String prefix) {
        Path stat = Path.of("/proc/" + serverPid + "/stat");
        Path self = Path.of("/proc/self/stat");
        long lastTicks = -1;
        long lastSelf = -1;
        long lastAt = 0;
        long nextSlow = System.nanoTime();
        long lastDump = 0;
        try (Connection db = DriverManager.getConnection(jdbcUrl, user, password);
                BufferedWriter timeline = Files.newBufferedWriter(Path.of(prefix + "-timeline.jsonl"))) {
            while (running.get()) {
                long now = System.nanoTime();
                long ticks = cpuTicks(stat);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("t", Math.round((now - t0) / 1e6) / 1e3);
                if (lastTicks >= 0 && ticks >= 0) {
                    double cores = (ticks - lastTicks) / 100.0 / ((now - lastAt) / 1e9);
                    cpuSamples.add(new double[] {now - t0, cores});
                    line.put("server_cores", Math.round(cores * 100) / 100.0);
                }
                long oldest = inFlight.values().stream()
                        .mapToLong(start -> now - start)
                        .max()
                        .orElse(0);
                line.put("in_flight", inFlight.size());
                line.put("oldest_ms", oldest / 1_000_000);
                line.putAll(pgWaits(db));
                timeline.write(JSON.writer()
                        .without(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(line));
                timeline.newLine();
                boolean stalled = oldest / 1_000_000 > STALL_MS;
                if (stalled && dumps.size() < MAX_DUMPS && now - lastDump > TimeUnit.SECONDS.toNanos(5)) {
                    String file = prefix + "-dump-" + Math.round((now - t0) / 1e9) + "s.txt";
                    new ProcessBuilder("jcmd", Long.toString(serverPid), "Thread.print")
                            .redirectErrorStream(true)
                            .redirectOutput(Path.of(file).toFile())
                            .start();
                    // 부하 생성기 자신의 스레드도 남긴다. 서버 작업 스레드가 놀고 있는데 요청이 걸려 있으면 생성기 쪽을 본다
                    String loadDump = prefix + "-loaddump-" + Math.round((now - t0) / 1e9) + "s.txt";
                    new ProcessBuilder(
                                    "jcmd",
                                    Long.toString(ProcessHandle.current().pid()),
                                    "Thread.print")
                            .redirectErrorStream(true)
                            .redirectOutput(Path.of(loadDump).toFile())
                            .start();
                    dumps.add(file);
                    dumps.add(loadDump);
                    lastDump = now;
                }
                long selfTicks = cpuTicks(self);
                if (lastSelf >= 0 && selfTicks >= 0) {
                    loadCpuSamples.add(
                            new double[] {now - t0, (selfTicks - lastSelf) / 100.0 / ((now - lastAt) / 1e9)});
                }
                lastSelf = selfTicks;
                lastTicks = ticks;
                lastAt = now;
                if (now >= nextSlow) {
                    Map<String, Object> row = new LinkedHashMap<>(scrape());
                    row.put("t", (now - t0) / 1e9);
                    row.putAll(pgSessions(db));
                    row.put("pg_cpu_usec", pgCpuUsec(pgContainer));
                    serverSamples.add(row);
                    nextSlow = now + TimeUnit.SECONDS.toNanos(1);
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
            }
        } catch (SQLException | IOException e) {
            System.err.println("[bench] sampler stopped: " + e.getMessage());
        }
    }

    /** 예매 서비스 세션의 상태·대기 종류별 수와 autovacuum 작업. 멈춘 순간 DB가 무엇을 기다리는지 본다. */
    static Map<String, Object> pgWaits(Connection db) {
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, Integer> waits = new LinkedHashMap<>();
        try (PreparedStatement query =
                        db.prepareStatement("SELECT state || ':' || coalesce(wait_event_type, '-') || ':'"
                                + " || coalesce(wait_event, '-'), count(*) FROM pg_stat_activity"
                                + " WHERE backend_type = 'client backend' AND usename = 'reservation_app'"
                                + " AND pid <> pg_backend_pid() AND state <> 'idle' GROUP BY 1");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                waits.put(rows.getString(1), rows.getInt(2));
            }
        } catch (SQLException e) {
            row.put("pg_error", e.getMessage());
        }
        row.put("pg", waits);
        List<String> workers = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement("SELECT left(coalesce(query, ''), 80) FROM pg_stat_activity"
                        + " WHERE backend_type = 'autovacuum worker'");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                workers.add(rows.getString(1));
            }
        } catch (SQLException e) {
            row.put("pg_error", e.getMessage());
        }
        if (!workers.isEmpty()) {
            row.put("autovacuum", workers);
        }
        return row;
    }

    /** 보내기로 한 초마다 다른 좌석 선점 p99·최댓값과 burst 최댓값. 멈춘 시각을 타임라인과 맞춰 본다. */
    List<Map<String, Object>> perSecond(int totalSeconds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int sec = 0; sec < totalSeconds; sec++) {
            long from = TimeUnit.SECONDS.toNanos(sec);
            long to = from + TimeUnit.SECONDS.toNanos(1);
            List<Double> base = samples.stream()
                    .filter(x -> x.load().equals("base") && x.kind().equals("hold"))
                    .filter(x -> x.intendedNanos() >= from && x.intendedNanos() < to)
                    .map(Sample::latencyMs)
                    .sorted()
                    .toList();
            double burstMax = samples.stream()
                    .filter(x -> x.load().equals("burst") && x.kind().equals("hold"))
                    .filter(x -> x.intendedNanos() >= from && x.intendedNanos() < to)
                    .mapToDouble(Sample::latencyMs)
                    .max()
                    .orElse(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("s", sec);
            row.put("base_p99", base.isEmpty() ? null : percentile(base, 0.99));
            row.put("base_max", base.isEmpty() ? null : base.get(base.size() - 1));
            row.put("burst_max", burstMax);
            out.add(row);
        }
        return out;
    }

    /** /proc/{pid}/stat의 utime+stime(클록 틱, 초당 100). 읽지 못하면 -1. */
    static long cpuTicks(Path stat) {
        try {
            String text = Files.readString(stat);
            String[] fields = text.substring(text.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** 기본 풀(booking-main)의 Hikari 지표와 Tomcat 바쁜 스레드, GC 멈춤 누적 시간을 읽는다. */
    Map<String, Object> scrape() {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/prometheus"))
                            .timeout(Duration.ofSeconds(2))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            double gc = 0;
            for (String line : response.body().split("\n")) {
                Matcher m = METRIC.matcher(line);
                if (!m.find()) continue;
                String name = m.group(1);
                String labels = m.group(2) == null ? "" : m.group(2);
                double value = Double.parseDouble(m.group(3));
                boolean main = labels.contains("pool=\"booking-main\"");
                switch (name) {
                    case "hikaricp_connections_pending" -> {
                        if (main) row.put("hikari_pending", value);
                    }
                    case "hikaricp_connections_active" -> {
                        if (main) row.put("hikari_active", value);
                    }
                    case "hikaricp_connections_max" -> {
                        if (main) row.put("hikari_max", value);
                    }
                    case "hikaricp_connections_acquire_seconds_max" -> {
                        if (main) row.put("hikari_acquire_max_ms", value * 1000);
                    }
                    case "tomcat_threads_busy_threads" -> row.put("tomcat_busy", value);
                    case "reservation_seat_hold_first_read_total" -> {
                        // 선점의 처음 좌석 읽기 경로 누적(#447). 스냅샷 경로가 실제로 쓰였는지 본다
                        row.put(
                                labels.contains("source=\"snapshot\"") ? "first_read_snapshot" : "first_read_db",
                                value);
                    }
                    case "http_server_requests_seconds_max" -> {
                        // 서버가 직접 잰 선점 요청 처리 시간(구간 최댓값). 생성기에서 본 지연과 비교한다
                        if (labels.contains("method=\"POST\"") && labels.contains("/holds")) {
                            row.merge("hold_server_max_ms", value * 1000, (a, b) -> Math.max((double) a, (double) b));
                        }
                    }
                    case "jvm_gc_pause_seconds_sum" -> gc += value;
                    case "reservation_seat_hold_stage_seconds_sum" ->
                        // 단계별 누적 시간(#449 후속). 초마다 차이를 보면 멈춘 초에 어느 단계가 시간을 썼는지 안다
                        // 모드도 키에 넣는다. 같은 서버에서 두 모드를 번갈아 재므로, 빼면 같은 단계의 DB·REDIS 값이 서로 덮어쓴다
                        row.put("stage_s:" + stageKey(labels), value);
                    case "reservation_seat_hold_conn_wait_seconds_sum" ->
                        row.put("conn_wait_s:" + stageKey(labels), value);
                    default -> {}
                }
            }
            row.put("gc_pause_seconds_total", gc);
        } catch (IOException e) {
            row.put("scrape_error", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return row;
    }

    static Map<String, Object> pgSessions(Connection db) {
        Map<String, Object> row = new LinkedHashMap<>();
        try (PreparedStatement query =
                        db.prepareStatement("SELECT count(*), count(*) FILTER (WHERE usename = 'reservation_app'),"
                                + " count(*) FILTER (WHERE wait_event_type = 'Lock')"
                                + " FROM pg_stat_activity WHERE backend_type = 'client backend'");
                ResultSet rows = query.executeQuery()) {
            rows.next();
            row.put("pg_connections", rows.getInt(1));
            row.put("pg_connections_reservation", rows.getInt(2));
            row.put("pg_lock_waits", rows.getInt(3));
        } catch (SQLException e) {
            row.put("pg_error", e.getMessage());
        }
        return row;
    }

    /** DB 컨테이너 cgroup의 누적 CPU 사용 시간(us). 읽지 못하면 -1. */
    static long pgCpuUsec(String container) {
        try {
            Process process = new ProcessBuilder("docker", "exec", container, "head", "-1", "/sys/fs/cgroup/cpu.stat")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor(5, TimeUnit.SECONDS);
            return Long.parseLong(out.split("\\s+")[1]);
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 측정 구간에 보내기로 한 요청만 모아 지연 분포와 결과별 건수를 낸다. */
    Map<String, Object> summarize(String load, long from, long to, int expected) {
        List<Sample> holds = new ArrayList<>();
        List<Double> releases = new ArrayList<>();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        int late = 0;
        int releaseFailed = 0;
        for (Sample s : samples) {
            if (!s.load().equals(load) || s.intendedNanos() < from || s.intendedNanos() >= to) continue;
            if (s.kind().equals("hold")) {
                holds.add(s);
                outcomes.merge(s.outcome(), 1, Integer::sum);
                if (s.lateMs() > LATE_MS) late++;
            } else {
                releases.add(s.latencyMs());
                if (!s.outcome().equals("released")) releaseFailed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expected", expected);
        out.put("attempts", holds.size());
        out.put("outcomes", outcomes);
        out.put("late_ratio", holds.isEmpty() ? null : (double) late / holds.size());
        out.put("hold_ms", distribution(holds.stream().map(Sample::latencyMs).toList()));
        out.put("release_ms", distribution(releases));
        out.put("release_failed", releaseFailed);
        return out;
    }

    Map<String, Object> summarizeServer(long t0, long from, long to) {
        double fromSec = from / 1e9;
        double toSec = to / 1e9;
        List<Double> cores = cpuSamples.stream()
                .filter(c -> c[0] >= from && c[0] < to)
                .map(c -> c[1])
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> cpu = distribution(cores);
        cpu.put("cpus", SERVER_CPUS);
        cpu.put(
                "saturated_ratio",
                cores.isEmpty()
                        ? null
                        : cores.stream()
                                        .filter(c -> c >= SERVER_CPUS * CPU_SATURATED)
                                        .count()
                                / (double) cores.size());
        out.put("server_cpu_200ms", cpu);
        out.put(
                "load_cpu_200ms",
                distribution(loadCpuSamples.stream()
                        .filter(c -> c[0] >= from && c[0] < to)
                        .map(c -> c[1])
                        .toList()));
        List<Map<String, Object>> rows = serverSamples.stream()
                .filter(r -> (double) r.get("t") >= fromSec && (double) r.get("t") < toSec)
                .toList();
        for (String key : List.of(
                "hikari_pending",
                "hikari_active",
                "hikari_max",
                "hikari_acquire_max_ms",
                "hold_server_max_ms",
                "tomcat_busy",
                "pg_connections",
                "pg_connections_reservation",
                "pg_lock_waits")) {
            out.put(
                    key,
                    distribution(rows.stream()
                            .filter(r -> r.get(key) instanceof Number)
                            .map(r -> ((Number) r.get(key)).doubleValue())
                            .toList()));
        }
        out.put("gc_pause_ratio", rate(rows, "gc_pause_seconds_total", 1));
        out.put("pg_cpu_cores", rate(rows, "pg_cpu_usec", 1e6));
        out.put("samples", rows.size());
        return out;
    }

    /** 누적값의 구간 평균 증가율(단위/초). 표본이 둘 미만이거나 읽지 못한 값이 있으면 null. */
    static Double rate(List<Map<String, Object>> rows, String key, double scale) {
        List<Map<String, Object>> valid = rows.stream()
                .filter(r -> r.get(key) instanceof Number n && n.doubleValue() >= 0)
                .toList();
        if (valid.size() < 2) return null;
        Map<String, Object> first = valid.get(0);
        Map<String, Object> last = valid.get(valid.size() - 1);
        double seconds = (double) last.get("t") - (double) first.get("t");
        double delta = ((Number) last.get(key)).doubleValue() - ((Number) first.get(key)).doubleValue();
        return seconds > 0 ? delta / scale / seconds : null;
    }

    static Map<String, Object> distribution(List<Double> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values.isEmpty()) {
            out.put("count", 0);
            return out;
        }
        List<Double> sorted = values.stream().sorted().toList();
        out.put("count", sorted.size());
        out.put(
                "avg",
                sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        out.put("p50", percentile(sorted, 0.50));
        out.put("p95", percentile(sorted, 0.95));
        out.put("p99", percentile(sorted, 0.99));
        out.put("max", sorted.get(sorted.size() - 1));
        return out;
    }

    static double percentile(List<Double> sorted, double q) {
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    // ---------- 모의 매진(#447) ----------

    /** 모의 매진에서 동시에 여는 회차 수. 회차마다 좌석 SEATS석, 사용자 ACTIVE명이다. */
    static final int SCHEDULES = Integer.getInteger("bench.schedules", 1);
    /** 구역 하나의 좌석 수. 25,000석이면 S01\~S25 25개 구역이다. */
    static final int SECTION_SEATS = Integer.getInteger("bench.section.seats", 1_000);
    /** 앞쪽 인기 구역 수(S01부터). */
    static final int HOT_SECTIONS = Integer.getInteger("bench.hot.sections", 5);
    /** 인기 구역에 빈 좌석이 보이는 동안 거기서 고르는 사용자 비율(%). 인기 구역이 다 팔리면 다른 구역으로 간다. */
    static final int HOT_PERCENT = Integer.getInteger("bench.hot.percent", 70);
    /** 선점에 성공한 뒤 결제를 포기하고 해제하는 비율(%). 가정이다. */
    static final int ABANDON_PERCENT = Integer.getInteger("bench.abandon.percent", 20);
    /**
     * 좌석 화면의 상태 자동 갱신 간격. 스냅샷이 최대 1초마다 갱신되므로 1초가 의미 있는 가장 짧은 간격이다. 프런트 SeatsPage(3초)보다
     * 가혹하게 둔다(조회 부하가 가장 큼). 409를 받으면 바로 다시 받는다.
     */
    static final long VIEW_REFRESH_MS = Long.getLong("bench.view.refresh.ms", 1_000);
    /** 같은 구역에서 409를 이만큼 연달아 받으면 인기 구역을 포기하고 빈 좌석이 있는 아무 구역에서 다시 고른다. */
    static final int GIVE_UP_AFTER = Integer.getInteger("bench.give.up.after", 3);

    /**
     * 대기열 입장 속도(회차마다 초당 입장 인원). 0이면 빈자리가 나는 즉시 다음 사람이 들어온다(입장 제한 없는 최악의 경우).
     * 대기열(admit.lua)처럼 1초마다 1초 분량을 들여보내고, 못 쓴 입장분은 2초 분량까지만 쌓는다. 안에 있는 인원 상한은 ACTIVE다.
     */
    static final int ADMIT_PER_SECOND = Integer.getInteger("bench.admit", 0);

    /**
     * 선점 위치(#449). DB 또는 REDIS를 주면 회차를 시작하기 전에 측정 서버의 스위치를 바꾼다. 비우면 서버 기동 설정 그대로다.
     * 같은 서버에서 두 모드를 번갈아 재려고 스크립트가 단계마다 넘긴다.
     */
    static final String HOLD_MODE = System.getProperty("bench.holdMode", "");

    /**
     * REDIS 모드에서 선점 뒤 결제하기를 누르기까지(결제 화면에 머무는 시간). 짧을수록 DB 쓰기가 오픈 직후에 몰려 Redis 효과가 작게
     * 나온다. 실제 사용자는 쿠폰·결제 수단을 고르느라 더 길 것으로 보아, 보수적으로 5\~15초로 둔다(가정).
     */
    static final int CONVERT_MIN_MS = Integer.getInteger("bench.convert.min", 5_000);

    static final int CONVERT_MAX_MS = Integer.getInteger("bench.convert.max", 15_000);

    /** 측정 끝에 남은 Redis 선점 수를 읽을 Redis 컨테이너 이름(실행 스크립트의 hb-redis). */
    static final String REDIS_CONTAINER = System.getProperty("bench.redisContainer", "hb-redis");

    /** 모의 매진 요청 한 건. kind는 hold·view(구역 상태)·sections(구역 목록)·release, t는 시작 기준 경과 시간(ns)이다. */
    record SelloutSample(int k, String kind, long t, double latencyMs, String outcome) {}

    /** 선점 결과와 성공한 경우의 예약 id(DB 방식) 또는 holdId(REDIS 모드, #449). */
    record HoldOutcome(String outcome, long reservationId, String holdId) {}

    /**
     * 성공·업무 거절이 아닌 요청 한 건(#449 후속). error 하나로 세면 서버 오류인지, 응답을 못 받은 것인지 나눌 수 없다.
     * 회원·holdId는 이 파일(prefix-errors.jsonl)과 결과의 errors에만 남긴다. at은 System.nanoTime 값이다.
     */
    record ErrorDetail(
            long at,
            String kind,
            String category,
            int status,
            String code,
            String exception,
            long scheduleId,
            long userId,
            String id,
            double latencyMs) {}

    private final Queue<ErrorDetail> errors = new ConcurrentLinkedQueue<>();

    /** 응답을 받지 못한 요청의 종류. 정해진 값만 쓴다. */
    static String failureOf(Exception e) {
        if (e instanceof java.net.http.HttpConnectTimeoutException) return "connect_timeout";
        if (e instanceof java.net.http.HttpTimeoutException) return "timeout";
        if (e instanceof java.net.ConnectException) return "connect_refused";
        if (e instanceof InterruptedException) return "interrupted";
        return "io_error";
    }

    /** 응답은 받았지만 성공·업무 거절이 아닌 상태 코드의 종류. */
    static String statusCategory(int status) {
        if (status >= 500) return "server_error";
        if (status >= 400) return "client_error";
        return "unexpected_status";
    }

    private void recordError(
            long sent,
            String kind,
            String category,
            int status,
            JsonNode body,
            Exception e,
            long scheduleId,
            long userId,
            String id) {
        String code = body == null ? null : body.path("code").asText(null);
        errors.add(new ErrorDetail(
                sent,
                kind,
                category,
                status,
                code,
                e == null ? null : e.getClass().getName() + ": " + e.getMessage(),
                scheduleId,
                userId,
                id,
                (System.nanoTime() - sent) / 1e6));
    }

    private static String stageKey(String labels) {
        return label(labels, "flow") + "/" + label(labels, "stage") + "/" + label(labels, "mode");
    }

    private static String label(String labels, String name) {
        Matcher m = java.util.regex.Pattern.compile(name + "=\"([^\"]*)\"").matcher(labels);
        return m.find() ? m.group(1) : "-";
    }

    private final Queue<SelloutSample> selloutSamples = new ConcurrentLinkedQueue<>();

    /**
     * 처음부터 매진까지 실제로 팔아 본다. 회차 SCHEDULES개를 같은 서버에서 동시에 열고, 회차마다 안에 ACTIVE명이 있다.
     * 한 명이 선점에 성공해 나가면 다음 사람이 바로 들어온다(대기열 입장 속도 제한이 없는 최악의 경우).
     *
     * 사용자 한 명의 흐름(프런트 SeatsPage를 따름):
     *   1. 구역 목록(sections)을 받고 구역을 고른다. HOT_PERCENT%는 앞쪽 인기 구역에 빈 좌석이 있는 동안만 거기서 고른다.
     *   2. 그 구역의 좌석 상태(status?section=)를 받는다. 처음 고르기 CHOOSE_MIN_MS\~CHOOSE_MAX_MS(기본 1\~2초) 뒤 빈 좌석을 무작위로
     *      골라 선점한다. 상태는 VIEW_REFRESH_MS(1초)마다 다시 받는다.
     *   3. 성공하면 결제로 넘어가 나간다. ABANDON_PERCENT%는 10\~30초 뒤 해제한다(결제 포기). 좌석이 다시 풀린다.
     *      REDIS 모드(#449, 응답에 holdId가 있음)에서는 결제하는 사용자가 CONVERT_MIN_MS\~CONVERT_MAX_MS 뒤 결제하기를 누른다.
     *      결제 포기는 절반이 결제하기 전에, 절반은 결제하기 뒤 10\~30초에 예약을 해제한다(결제창에서 포기). 결제하기 전에만 포기한다고
     *      두면 DB에 쓰지 않는 사용자가 늘어 Redis 쪽이 유리하게 나온다.
     *   4. 409면 바로 상태를 다시 받고(프런트의 invalidateQueries), 1\~3초 뒤 다시 고른다. 구역에 빈 좌석이 없거나 같은 구역에서
     *      GIVE_UP_AFTER번 연달아 놓치면 인기 구역을 포기하고 1로 간다.
     * 회차마다 모든 구역에 빈 좌석이 없거나 SELLOUT_LIMIT_SECONDS가 지나면 끝낸다.
     */
    void runSellout(
            Path output,
            int pool,
            int rep,
            long scheduleId,
            List<Long> seats,
            long userBase,
            long serverPid,
            String jdbcUrl,
            String jdbcUser,
            String jdbcPassword,
            String pgContainer)
            throws Exception {
        // 첫 회차는 main이 만들었다. 나머지 회차를 더 만든다(회차 id는 첫 회차 뒤로 이어 붙인다)
        List<Long> scheduleIds = new ArrayList<>(List.of(scheduleId));
        List<List<Long>> seatLists = new ArrayList<>(List.of(seats));
        try (Connection db = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            for (int k = 1; k < SCHEDULES; k++) {
                long id = scheduleId + k * 100_000_000L;
                scheduleIds.add(id);
                seatLists.add(seed(db, id, SECTION_SEATS));
            }
        }
        int n = SCHEDULES;
        Map<String, Double> redisBefore = scrapeRedisMetrics();
        Map<String, Double> stageBefore =
                scrapeMetrics("reservation_seat_hold_stage", "reservation_seat_hold_conn_wait");
        errors.clear();
        long t0 = System.nanoTime();
        String prefix = output.toString().replaceFirst("\\.json$", "");
        Thread sampler =
                new Thread(() -> sampleServer(t0, serverPid, jdbcUrl, jdbcUser, jdbcPassword, pgContainer, prefix));
        sampler.setDaemon(true);
        sampler.start();

        AtomicInteger[] sold = new AtomicInteger[n];
        AtomicInteger[] released = new AtomicInteger[n];
        AtomicLong[] lastSoldAt = new AtomicLong[n];
        AtomicLong[] nextUser = new AtomicLong[n];
        AtomicBoolean[] soldOut = new AtomicBoolean[n];
        // 해제 예정(결제 포기)인 선점 수. 0이 아니면 빈 좌석이 없어 보여도 곧 다시 풀리므로 매진으로 보지 않는다
        AtomicInteger[] pendingReleases = new AtomicInteger[n];
        for (int k = 0; k < n; k++) {
            sold[k] = new AtomicInteger();
            released[k] = new AtomicInteger();
            lastSoldAt[k] = new AtomicLong();
            nextUser[k] = new AtomicLong(userBase + 1 + k * 200_000L);
            soldOut[k] = new AtomicBoolean();
            pendingReleases[k] = new AtomicInteger();
        }
        long deadline = t0 + TimeUnit.SECONDS.toNanos(SELLOUT_LIMIT_SECONDS);
        java.util.concurrent.ScheduledExecutorService abandoner = Executors.newScheduledThreadPool(16);
        // 해제·결제하기 요청은 예약 스레드가 아니라 여기서 보낸다. 예약 스레드(16개)에서 보내면 서버가 느릴 때 줄을 서 지연이 부풀려진다
        ExecutorService actions = Executors.newCachedThreadPool();
        if (!HOLD_MODE.isBlank()) {
            setHoldMode(HOLD_MODE);
        }
        ExecutorService users = Executors.newFixedThreadPool(ACTIVE * n);
        java.util.concurrent.Semaphore[] admits = new java.util.concurrent.Semaphore[n];
        for (int k = 0; k < n; k++) {
            admits[k] = new java.util.concurrent.Semaphore(0);
        }
        if (ADMIT_PER_SECOND > 0) {
            abandoner.scheduleAtFixedRate(
                    () -> {
                        for (java.util.concurrent.Semaphore admit : admits) {
                            admit.release(Math.max(
                                    0, Math.min(ADMIT_PER_SECOND, 2 * ADMIT_PER_SECOND - admit.availablePermits())));
                        }
                    },
                    0,
                    1,
                    TimeUnit.SECONDS);
        }
        for (int k = 0; k < n; k++) {
            int kk = k;
            long sid = scheduleIds.get(k);
            List<Long> seatIds = seatLists.get(k);
            for (int i = 0; i < ACTIVE; i++) {
                users.execute(() -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    while (System.nanoTime() < deadline && !soldOut[kk].get()) {
                        if (ADMIT_PER_SECOND > 0) {
                            try {
                                if (!admits[kk].tryAcquire(200, TimeUnit.MILLISECONDS)) {
                                    continue;
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        long userId = nextUser[kk].getAndIncrement();
                        boolean hotFan = random.nextInt(100) < HOT_PERCENT;
                        int misses = 0;
                        boolean left = false;
                        while (!left && System.nanoTime() < deadline) {
                            int[] counts = readSections(kk, sid, userId, t0);
                            if (counts == null) {
                                pause(1_000);
                                continue;
                            }
                            int section = pickSection(counts, hotFan, random);
                            if (section < 0) {
                                if (pendingReleases[kk].get() == 0) {
                                    soldOut[kk].set(true);
                                    return;
                                }
                                pause(1_000);
                                continue;
                            }
                            long[] viewedAt = {System.nanoTime()};
                            int[] view = readSectionView(kk, sid, userId, section, t0);
                            // 좌석을 고르는 동안에도 화면은 VIEW_REFRESH_MS마다 상태를 다시 받는다
                            view = watch(
                                    kk,
                                    sid,
                                    userId,
                                    section,
                                    t0,
                                    random.nextLong(CHOOSE_MIN_MS, CHOOSE_MAX_MS + 1L),
                                    view,
                                    viewedAt);
                            while (System.nanoTime() < deadline) {
                                if (System.nanoTime() - viewedAt[0] > TimeUnit.MILLISECONDS.toNanos(VIEW_REFRESH_MS)) {
                                    view = readSectionView(kk, sid, userId, section, t0);
                                    viewedAt[0] = System.nanoTime();
                                }
                                if (view == null || view.length == 0) {
                                    break;
                                }
                                long seatId = seatIds.get(view[random.nextInt(view.length)]);
                                long start = System.nanoTime();
                                HoldOutcome result = holdOnce(sid, userId, seatId);
                                long end = System.nanoTime();
                                selloutSamples.add(new SelloutSample(
                                        kk, "hold", start - t0, (end - start) / 1e6, result.outcome()));
                                if ("created".equals(result.outcome())) {
                                    sold[kk].incrementAndGet();
                                    lastSoldAt[kk].set(end - t0);
                                    long uid = userId;
                                    boolean abandon = random.nextInt(100) < ABANDON_PERCENT;
                                    Runnable releaseLater = () -> {
                                        if (releaseOnce(kk, sid, uid, idOf(result), t0)) {
                                            sold[kk].decrementAndGet();
                                            released[kk].incrementAndGet();
                                        }
                                    };
                                    if (result.holdId() == null) {
                                        // DB 방식: 예약이 이미 있다. 결제 포기만 해제한다
                                        if (abandon) {
                                            later(
                                                    abandoner,
                                                    actions,
                                                    pendingReleases[kk],
                                                    random.nextLong(10_000, 30_001),
                                                    releaseLater);
                                        }
                                    } else if (abandon && random.nextBoolean()) {
                                        // REDIS 모드, 결제하기 전에 포기
                                        later(
                                                abandoner,
                                                actions,
                                                pendingReleases[kk],
                                                random.nextLong(10_000, 30_001),
                                                releaseLater);
                                    } else {
                                        // REDIS 모드, 결제하기. 포기하는 사용자는 결제하기 뒤 결제창에서 포기해 예약을 해제한다
                                        later(
                                                abandoner,
                                                actions,
                                                pendingReleases[kk],
                                                random.nextLong(CONVERT_MIN_MS, CONVERT_MAX_MS + 1L),
                                                () -> {
                                                    long reservationId = convertOnce(kk, sid, uid, result.holdId(), t0);
                                                    if (reservationId < 0) {
                                                        // 결제하기에서 좌석을 잃었다(409·만료). 좌석은 다시 풀린다
                                                        sold[kk].decrementAndGet();
                                                        return;
                                                    }
                                                    if (abandon) {
                                                        later(
                                                                abandoner,
                                                                actions,
                                                                pendingReleases[kk],
                                                                random.nextLong(10_000, 30_001),
                                                                () -> {
                                                                    if (releaseOnce(
                                                                            kk,
                                                                            sid,
                                                                            uid,
                                                                            Long.toString(reservationId),
                                                                            t0)) {
                                                                        sold[kk].decrementAndGet();
                                                                        released[kk].incrementAndGet();
                                                                    }
                                                                });
                                                    }
                                                });
                                    }
                                    left = true;
                                    break;
                                }
                                if (!"taken".equals(result.outcome())) {
                                    left = true;
                                    break;
                                }
                                if (++misses >= GIVE_UP_AFTER) {
                                    // 같은 구역에서 계속 놓치면 다른 구역으로 옮긴다(인기 구역도 포기)
                                    hotFan = false;
                                    misses = 0;
                                    pause(random.nextLong(1_000, 3_001));
                                    break;
                                }
                                // 프런트는 409에서 좌석 상태를 바로 다시 받는다. 다시 고르는 동안에도 화면은 계속 갱신된다
                                view = readSectionView(kk, sid, userId, section, t0);
                                viewedAt[0] = System.nanoTime();
                                view = watch(
                                        kk, sid, userId, section, t0, random.nextLong(1_000, 3_001), view, viewedAt);
                            }
                        }
                    }
                });
            }
        }
        users.shutdown();
        boolean finished = users.awaitTermination(SELLOUT_LIMIT_SECONDS + 60L, TimeUnit.SECONDS);
        users.shutdownNow();
        abandoner.shutdownNow();
        actions.shutdownNow();
        running.set(false);
        sampler.join(5_000);

        long endNanos = System.nanoTime() - t0;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", "sellout");
        report.put("pool", pool);
        report.put("rep", rep);
        report.put("schedule_ids", scheduleIds);
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("seats", SEATS);
        workload.put("schedules", n);
        workload.put("active_users", ACTIVE);
        workload.put("admit_per_second", ADMIT_PER_SECOND);
        workload.put("section_seats", SECTION_SEATS);
        workload.put("hot_sections", HOT_SECTIONS);
        workload.put("hot_percent", HOT_PERCENT);
        workload.put("abandon_percent", ABANDON_PERCENT);
        workload.put("abandon_after_ms", "10000-30000");
        workload.put("hold_mode", HOLD_MODE.isBlank() ? null : HOLD_MODE);
        workload.put("convert_after_ms", CONVERT_MIN_MS + "-" + CONVERT_MAX_MS);
        workload.put("abandon_split", "REDIS: 절반은 결제하기 전, 절반은 결제하기 뒤 해제");
        workload.put("choose_ms", CHOOSE_MIN_MS + "-" + CHOOSE_MAX_MS);
        workload.put("retry_ms", "1000-3000");
        workload.put("view_refresh_ms", VIEW_REFRESH_MS);
        workload.put("limit_seconds", SELLOUT_LIMIT_SECONDS);
        workload.put("sale_validation_delay_ms", HoldBenchServerMain.SALE_VALIDATION_DELAY.toMillis());
        report.put("workload", workload);
        report.put("finished", finished);
        int soldTotal = 0;
        int releasedTotal = 0;
        boolean allSoldOut = true;
        double slowest = 0;
        List<Map<String, Object>> perSchedule = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int s = sold[k].get();
            soldTotal += s;
            releasedTotal += released[k].get();
            boolean out = soldOut[k].get();
            allSoldOut &= out;
            if (out) {
                slowest = Math.max(slowest, lastSoldAt[k].get() / 1e9);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schedule_id", scheduleIds.get(k));
            row.put("sold", s);
            row.put("released", released[k].get());
            row.put("sold_out", out);
            row.put("sellout_seconds", out ? lastSoldAt[k].get() / 1e9 : null);
            perSchedule.add(row);
        }
        report.put("per_schedule", perSchedule);
        report.put("sold", soldTotal);
        report.put("released", releasedTotal);
        // 매진: 회차마다 구역 목록에 빈 좌석이 하나도 없음을 본 시점. 마지막 성공 시각을 매진 시간으로 둔다
        report.put("sold_out", allSoldOut);
        report.put("sellout_seconds", allSoldOut ? slowest : null);
        List<SelloutSample> all = new ArrayList<>(selloutSamples);
        List<SelloutSample> holds =
                all.stream().filter(x -> "hold".equals(x.kind())).toList();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        holds.forEach(x -> outcomes.merge(x.outcome(), 1, Integer::sum));
        report.put("outcomes", outcomes);
        int created = outcomes.getOrDefault("created", 0);
        report.put("attempts_per_success", created == 0 ? null : (double) holds.size() / created);
        report.put("created_ms", distribution(latencies(all, "hold", "created", 0, endNanos)));
        report.put("taken_ms", distribution(latencies(all, "hold", "taken", 0, endNanos)));
        report.put("view_ms", distribution(latencies(all, "view", null, 0, endNanos)));
        report.put("sections_ms", distribution(latencies(all, "sections", null, 0, endNanos)));
        report.put("release_ms", distribution(latencies(all, "release", null, 0, endNanos)));
        Map<String, Integer> convertOutcomes = new LinkedHashMap<>();
        all.stream()
                .filter(x -> "convert".equals(x.kind()))
                .forEach(x -> convertOutcomes.merge(x.outcome(), 1, Integer::sum));
        report.put("convert_outcomes", convertOutcomes);
        report.put("convert_ms", distribution(latencies(all, "convert", null, 0, endNanos)));
        List<Map<String, Object>> windows = new ArrayList<>();
        for (long from = 0; from < endNanos; from += TimeUnit.SECONDS.toNanos(10)) {
            long to = from + TimeUnit.SECONDS.toNanos(10);
            long f = from;
            List<SelloutSample> in =
                    all.stream().filter(x -> x.t() >= f && x.t() < to).toList();
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("from_s", from / 1_000_000_000L);
            w.put(
                    "holds_per_s",
                    in.stream().filter(x -> "hold".equals(x.kind())).count() / 10.0);
            // 선점 표본만 센다. 결제하기(#449)도 결과가 created라 종류로 거르지 않으면 REDIS 모드에서 두 번 센다
            w.put(
                    "created_per_s",
                    in.stream()
                                    .filter(x -> "hold".equals(x.kind()) && "created".equals(x.outcome()))
                                    .count()
                            / 10.0);
            w.put(
                    "taken_per_s",
                    in.stream()
                                    .filter(x -> "hold".equals(x.kind()) && "taken".equals(x.outcome()))
                                    .count()
                            / 10.0);
            w.put(
                    "reads_per_s",
                    in.stream()
                                    .filter(x -> "view".equals(x.kind()) || "sections".equals(x.kind()))
                                    .count()
                            / 10.0);
            w.put(
                    "converts_per_s",
                    in.stream().filter(x -> "convert".equals(x.kind())).count() / 10.0);
            w.put(
                    "releases_per_s",
                    in.stream().filter(x -> "release".equals(x.kind())).count() / 10.0);
            w.put(
                    "other",
                    in.stream()
                            .filter(x -> "hold".equals(x.kind())
                                    && !"created".equals(x.outcome())
                                    && !"taken".equals(x.outcome()))
                            .count());
            w.put("created_ms", distribution(latencies(all, "hold", "created", from, to)));
            w.put("taken_ms", distribution(latencies(all, "hold", "taken", from, to)));
            w.put("view_ms", distribution(latencies(all, "view", null, from, to)));
            w.put("convert_ms", distribution(latencies(all, "convert", null, from, to)));
            windows.add(w);
        }
        report.put("windows_10s", windows);
        // 멈춤: 선점 응답(성공·409)의 초당 최댓값이 0.5초를 넘은 초(#447 판단 기준)
        List<Long> stalledSeconds = new ArrayList<>();
        for (long sec = 0; sec * 1_000_000_000L < endNanos; sec++) {
            long f = sec * 1_000_000_000L;
            long to = f + 1_000_000_000L;
            double max = holds.stream()
                    .filter(x -> x.t() >= f && x.t() < to)
                    .mapToDouble(SelloutSample::latencyMs)
                    .max()
                    .orElse(0);
            if (max > 500) {
                stalledSeconds.add(sec);
            }
        }
        report.put("stalled_seconds", stalledSeconds);
        // REDIS 모드에서는 DB 쓰기가 결제하기로 옮겨 가므로, 선점이나 결제하기 응답이 0.5초를 넘은 초를 따로 센다(#449)
        List<Long> stalledWithConvert = new ArrayList<>();
        for (long sec = 0; sec * 1_000_000_000L < endNanos; sec++) {
            long f = sec * 1_000_000_000L;
            long to = f + 1_000_000_000L;
            double max = all.stream()
                    .filter(x -> ("hold".equals(x.kind()) || "convert".equals(x.kind())) && x.t() >= f && x.t() < to)
                    .mapToDouble(SelloutSample::latencyMs)
                    .max()
                    .orElse(0);
            if (max > 500) {
                stalledWithConvert.add(sec);
            }
        }
        report.put("stalled_seconds_with_convert", stalledWithConvert);
        Map<String, Object> redis = redisMetrics(redisBefore, scrapeRedisMetrics());
        report.put("redis_metrics", redis);
        report.put("redis_flow", redisFlow(redis, scheduleIds));
        report.put(
                "stages",
                stageMetrics(
                        stageBefore, scrapeMetrics("reservation_seat_hold_stage", "reservation_seat_hold_conn_wait")));
        report.put("errors", errorReport(t0, jdbcUrl, jdbcUser, jdbcPassword, prefix));
        report.put("server", summarizeServer(t0, 0, endNanos));
        report.put("server_rows", new ArrayList<>(serverSamples));
        report.put("thread_dumps", dumps);
        Files.createDirectories(output.getParent());
        JSON.writeValue(output.toFile(), report);
        System.out.println("[bench] wrote " + output + " sold " + soldTotal + "/" + (SEATS * n) + ", released "
                + releasedTotal + ", sold out " + allSoldOut);
    }

    /**
     * millis 동안 화면을 보고 있는 사용자. VIEW_REFRESH_MS마다 구역 상태를 다시 받아 마지막 값을 돌려준다(프런트 refetchInterval).
     * viewedAt[0]은 마지막으로 받은 시각이고, 받을 때마다 고친다.
     */
    int[] watch(int k, long scheduleId, long userId, int section, long t0, long millis, int[] view, long[] viewedAt) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        long refresh = TimeUnit.MILLISECONDS.toNanos(VIEW_REFRESH_MS);
        int[] latest = view;
        while (true) {
            long now = System.nanoTime();
            long next = viewedAt[0] + refresh;
            if (next >= until) {
                LockSupport.parkNanos(until - now);
                return latest;
            }
            LockSupport.parkNanos(next - now);
            int[] fresh = readSectionView(k, scheduleId, userId, section, t0);
            viewedAt[0] = System.nanoTime();
            if (fresh != null) {
                latest = fresh;
            }
        }
    }

    /** 인기 구역 팬은 앞쪽 HOT_SECTIONS개 중 빈 좌석이 있는 구역을, 아니면(또는 인기 구역이 다 팔리면) 빈 좌석이 있는 아무 구역을 고른다. */
    static int pickSection(int[] counts, boolean hotFan, ThreadLocalRandom random) {
        List<Integer> hot = new ArrayList<>();
        List<Integer> any = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                any.add(i);
                if (i < HOT_SECTIONS) {
                    hot.add(i);
                }
            }
        }
        List<Integer> from = hotFan && !hot.isEmpty() ? hot : any;
        return from.isEmpty() ? -1 : from.get(random.nextInt(from.size()));
    }

    private static List<Double> latencies(List<SelloutSample> all, String kind, String outcome, long from, long to) {
        return all.stream()
                .filter(x -> kind.equals(x.kind()) && (outcome == null || outcome.equals(x.outcome())))
                .filter(x -> x.t() >= from && x.t() < to)
                .map(SelloutSample::latencyMs)
                .toList();
    }

    private static void pause(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /** 선점 한 번. 성공해도 바로 해제하지 않는다(결제로 넘어간 사용자). */
    HoldOutcome holdOnce(long scheduleId, long userId, long seatId) {
        HttpRequest hold = request("/api/seats/schedules/" + scheduleId + "/holds", userId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scheduleSeatId\":" + seatId + "}"))
                .build();
        long id = requestSeq.incrementAndGet();
        long sent = System.nanoTime();
        inFlight.put(id, sent);
        try {
            HttpResponse<String> response = http.send(hold, HttpResponse.BodyHandlers.ofString());
            JsonNode body = parse(response.body());
            JsonNode holdId = body.path("data").path("holdId");
            String outcome = classify(response.statusCode(), body);
            if ("error".equals(outcome)) {
                int status = response.statusCode();
                String category =
                        status == 409 || status == 400 || status == 404 ? "business_reject" : statusCategory(status);
                recordError(sent, "hold", category, status, body, null, scheduleId, userId, Long.toString(seatId));
            }
            return new HoldOutcome(
                    outcome,
                    body.path("data").path("reservationId").asLong(0),
                    holdId.isTextual() ? holdId.asText() : null);
        } catch (IOException e) {
            recordError(sent, "hold", failureOf(e), 0, null, e, scheduleId, userId, Long.toString(seatId));
            return new HoldOutcome("error", 0, null);
        } catch (InterruptedException e) {
            recordError(sent, "hold", failureOf(e), 0, null, e, scheduleId, userId, Long.toString(seatId));
            Thread.currentThread().interrupt();
            return new HoldOutcome("error", 0, null);
        } finally {
            inFlight.remove(id);
        }
    }

    /** 이름이 prefixes 중 하나로 시작하는 지표 줄만 읽는다. 키는 지표 이름과 라벨, 값은 누적값이다. 읽지 못하면 빈 맵이다. */
    Map<String, Double> scrapeMetrics(String... prefixes) {
        Map<String, Double> series = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/prometheus"))
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            for (String line : response.body().split("\n")) {
                if (java.util.Arrays.stream(prefixes).noneMatch(line::startsWith)) {
                    continue;
                }
                int space = line.lastIndexOf(' ');
                series.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
            }
        } catch (IOException | RuntimeException e) {
            return series;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return series;
    }

    /**
     * 회차 동안의 단계별 시간과 DB 연결 대기(#449 후속, 서버 HoldStages). 키는 "stage|conn_wait:흐름/단계/모드"이고 값은 수·합계(초)·
     * 평균·p50·p99·p999(ms)다. 합계로 단계가 전체 시간에서 차지한 몫을 본다.
     */
    static Map<String, Object> stageMetrics(Map<String, Double> before, Map<String, Double> after) {
        java.util.regex.Pattern label = java.util.regex.Pattern.compile("(\\w+)=\"([^\"]*)\"");
        Map<String, java.util.TreeMap<Double, Double>> buckets = new java.util.TreeMap<>();
        Map<String, double[]> countSum = new java.util.TreeMap<>();
        for (Map.Entry<String, Double> entry : after.entrySet()) {
            String key = entry.getKey();
            double delta = entry.getValue() - before.getOrDefault(key, 0.0);
            Map<String, String> labels = new LinkedHashMap<>();
            java.util.regex.Matcher m = label.matcher(key);
            while (m.find()) {
                labels.put(m.group(1), m.group(2));
            }
            String family = key.startsWith("reservation_seat_hold_conn_wait") ? "conn_wait" : "stage";
            String name = family + ":" + labels.get("flow") + "/" + labels.get("stage") + "/" + labels.get("mode");
            if (key.contains("_seconds_bucket")) {
                String le = labels.get("le");
                double bound = "+Inf".equals(le) ? Double.POSITIVE_INFINITY : Double.parseDouble(le);
                buckets.computeIfAbsent(name, c -> new java.util.TreeMap<>()).put(bound, delta);
            } else if (key.contains("_seconds_count")) {
                countSum.computeIfAbsent(name, c -> new double[2])[0] = delta;
            } else if (key.contains("_seconds_sum")) {
                countSum.computeIfAbsent(name, c -> new double[2])[1] = delta;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : countSum.entrySet()) {
            double n = entry.getValue()[0];
            if (n == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", (long) n);
            row.put("sum_s", entry.getValue()[1]);
            row.put("mean_ms", entry.getValue()[1] * 1000 / n);
            java.util.TreeMap<Double, Double> cumulative =
                    buckets.getOrDefault(entry.getKey(), new java.util.TreeMap<>());
            row.put("p50_ms", quantileMs(cumulative, n, 0.50));
            row.put("p99_ms", quantileMs(cumulative, n, 0.99));
            row.put("p999_ms", quantileMs(cumulative, n, 0.999));
            out.put(entry.getKey(), row);
        }
        return out;
    }

    /**
     * 성공·업무 거절이 아닌 요청(#449 후속). 종류별 수를 세고, 한 건씩 prefix-errors.jsonl에 남긴다. 결제하기·선점 오류는 측정이 끝난
     * 뒤 그 회원·회차의 예약 상태를 DB에서 읽어 붙인다. 측정 끝의 상태라 오류 직후 상태와 다를 수 있다(뒤이은 해제·만료).
     */
    Map<String, Object> errorReport(long t0, String jdbcUrl, String jdbcUser, String jdbcPassword, String prefix)
            throws IOException {
        List<ErrorDetail> list = new ArrayList<>(errors);
        Map<String, Integer> kinds = new java.util.TreeMap<>();
        list.forEach(e -> kinds.merge(e.kind() + "/" + e.category(), 1, Integer::sum));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection db = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
                PreparedStatement query = db.prepareStatement("SELECT reservation_id, status FROM p_reservations"
                        + " WHERE user_id = ? AND schedule_id = ? ORDER BY reservation_id");
                BufferedWriter out = Files.newBufferedWriter(Path.of(prefix + "-errors.jsonl"))) {
            for (ErrorDetail e : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("t_s", (e.at() - t0) / 1e9);
                row.put("kind", e.kind());
                row.put("category", e.category());
                row.put("status", e.status());
                row.put("code", e.code());
                row.put("exception", e.exception());
                row.put("latency_ms", e.latencyMs());
                row.put("schedule_id", e.scheduleId());
                row.put("user_id", e.userId());
                row.put("id", e.id());
                if (!"business_reject".equals(e.category())) {
                    query.setLong(1, e.userId());
                    query.setLong(2, e.scheduleId());
                    List<String> reservations = new ArrayList<>();
                    try (java.sql.ResultSet rs = query.executeQuery()) {
                        while (rs.next()) {
                            reservations.add(rs.getLong(1) + ":" + rs.getString(2));
                        }
                    }
                    row.put("reservations_at_end", reservations);
                }
                // JSON Lines: 한 건을 한 줄에 쓴다(JSON은 들여쓰기 출력이라 따로 끈다)
                out.write(JSON.writer()
                        .without(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(row));
                out.newLine();
                if (rows.size() < 200) {
                    rows.add(row);
                }
            }
        } catch (java.sql.SQLException e) {
            kinds.put("reconcile_failed", 1);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("counts", kinds);
        report.put("first", rows);
        return report;
    }

    /** 서버의 Redis 선점 지표(#449) 줄만 읽는다. 키는 지표 이름과 라벨, 값은 누적값이다. 읽지 못하면 빈 맵이다. */
    Map<String, Double> scrapeRedisMetrics() {
        Map<String, Double> series = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/prometheus"))
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            for (String line : response.body().split("\n")) {
                if (!line.startsWith("reservation_seat_hold_redis")) {
                    continue;
                }
                int space = line.lastIndexOf(' ');
                series.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
            }
        } catch (IOException | RuntimeException e) {
            return series;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return series;
    }

    /**
     * 회차 동안의 Redis 선점 지표(#449). 서버를 여러 회차가 함께 쓰므로 시작·끝 값의 차이로 계산한다.
     * counts: 선점·해제·결제하기 결과별 수. calls: Redis 명령별 호출 수·평균·p50·p99(히스토그램 구간의 위 경계, ms).
     */
    static Map<String, Object> redisMetrics(Map<String, Double> before, Map<String, Double> after) {
        java.util.regex.Pattern label = java.util.regex.Pattern.compile("(\\w+)=\"([^\"]*)\"");
        Map<String, Double> counts = new java.util.TreeMap<>();
        Map<String, java.util.TreeMap<Double, Double>> buckets = new java.util.TreeMap<>();
        Map<String, double[]> countSum = new java.util.TreeMap<>();
        for (Map.Entry<String, Double> entry : after.entrySet()) {
            String key = entry.getKey();
            double delta = entry.getValue() - before.getOrDefault(key, 0.0);
            Map<String, String> labels = new LinkedHashMap<>();
            java.util.regex.Matcher m = label.matcher(key);
            while (m.find()) {
                labels.put(m.group(1), m.group(2));
            }
            if (key.startsWith("reservation_seat_hold_redis_total")) {
                counts.put(labels.get("operation") + "/" + labels.get("result"), delta);
            } else if (key.startsWith("reservation_seat_hold_redis_call_seconds_bucket")) {
                String le = labels.get("le");
                double bound = "+Inf".equals(le) ? Double.POSITIVE_INFINITY : Double.parseDouble(le);
                buckets.computeIfAbsent(labels.get("command"), c -> new java.util.TreeMap<>())
                        .put(bound, delta);
            } else if (key.startsWith("reservation_seat_hold_redis_call_seconds_count")) {
                countSum.computeIfAbsent(labels.get("command"), c -> new double[2])[0] = delta;
            } else if (key.startsWith("reservation_seat_hold_redis_call_seconds_sum")) {
                countSum.computeIfAbsent(labels.get("command"), c -> new double[2])[1] = delta;
            }
        }
        Map<String, Object> calls = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : countSum.entrySet()) {
            double n = entry.getValue()[0];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", (long) n);
            row.put("mean_ms", n == 0 ? null : entry.getValue()[1] * 1000 / n);
            java.util.TreeMap<Double, Double> cumulative =
                    buckets.getOrDefault(entry.getKey(), new java.util.TreeMap<>());
            row.put("p50_ms", quantileMs(cumulative, n, 0.50));
            row.put("p99_ms", quantileMs(cumulative, n, 0.99));
            row.put("p999_ms", quantileMs(cumulative, n, 0.999));
            calls.put(entry.getKey(), row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("calls", calls);
        return out;
    }

    /** 누적 히스토그램 구간에서 q 분위가 들어가는 구간의 위 경계(ms). 구간 안의 위치는 모르므로 위쪽으로 잡는다. */
    private static Double quantileMs(java.util.TreeMap<Double, Double> cumulative, double total, double q) {
        if (total <= 0) {
            return null;
        }
        for (Map.Entry<Double, Double> bucket : cumulative.entrySet()) {
            if (bucket.getValue() >= q * total) {
                return bucket.getKey() * 1000;
            }
        }
        return null;
    }

    /**
     * Redis 선점 수 흐름(#449 예매 담당 리뷰 5번). 예매의 만료·해제 지표는 예약이 있어야 세므로 Redis 선점은 따로 센다.
     * Redis 키가 수명이 끝나 사라지는 만료는 알려 오는 이벤트가 없어, 생성 − 해제 − 결제하기 성공 − 결제하기 실패로 지움 −
     * 측정 끝에 남은 선점으로 계산한다. 남은 선점은 회차마다 Redis 좌석 목록(held)의 크기다. 결제하기 중이거나 결과를 모르는 선점도
     * 목록에 남아 여기에 들어간다.
     * Note: 회차 측정(최대 몇 분)이 선점 유지 시간(10분)보다 짧아 만료는 0에 가깝게 나온다. 만료 비교는 선점 시간을 줄인 별도 측정으로 한다.
     */
    static Map<String, Object> redisFlow(Map<String, Object> redis, List<Long> scheduleIds) {
        @SuppressWarnings("unchecked")
        Map<String, Double> counts = (Map<String, Double>) redis.getOrDefault("counts", Map.of());
        long created = Math.round(counts.getOrDefault("hold/created", 0.0));
        long released = Math.round(counts.getOrDefault("release/released", 0.0));
        long converted = Math.round(counts.getOrDefault("convert/created", 0.0));
        long rejected = Math.round(counts.getOrDefault("convert/rejected", 0.0));
        Long remaining = 0L;
        for (Long scheduleId : scheduleIds) {
            Long size = zcard(REDIS_CONTAINER, "seathold:{s" + scheduleId + "}:held");
            if (size == null) {
                remaining = null;
                break;
            }
            remaining += size;
        }
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("created", created);
        flow.put("released", released);
        flow.put("converted", converted);
        flow.put("convert_rejected", rejected);
        flow.put("remaining", remaining);
        flow.put("expired_calc", remaining == null ? null : created - released - converted - rejected - remaining);
        return flow;
    }

    /** Redis 컨테이너에서 ZCARD. 읽지 못하면 null. */
    private static Long zcard(String container, String key) {
        try {
            Process process = new ProcessBuilder("docker", "exec", container, "redis-cli", "ZCARD", key)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return Long.parseLong(out);
        } catch (IOException | NumberFormatException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 해제 경로 값. REDIS 모드에서 결제하기 전이면 holdId, 아니면 예약 id다. */
    private static String idOf(HoldOutcome result) {
        return result.holdId() != null ? result.holdId() : Long.toString(result.reservationId());
    }

    /** delayMillis 뒤 actions에서 task를 실행한다. 실행이 끝날 때까지 pending에 세어 매진 판정을 미룬다. */
    private static void later(
            java.util.concurrent.ScheduledExecutorService scheduler,
            ExecutorService actions,
            AtomicInteger pending,
            long delayMillis,
            Runnable task) {
        pending.incrementAndGet();
        try {
            scheduler.schedule(
                    () -> actions.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            pending.decrementAndGet();
                        }
                    }),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 측정이 끝나 실행기가 닫혔다
            pending.decrementAndGet();
        }
    }

    /** 측정 서버의 선점 위치 스위치를 바꾼다(#449). 실패하면 측정을 멈춘다. 모드가 섞인 결과는 쓸 수 없다. */
    void setHoldMode(String mode) {
        try {
            HttpResponse<String> response = http.send(
                    request("/bench/hold-mode?mode=" + mode, 0)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("선점 위치를 바꾸지 못했다: " + response.statusCode() + " " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("선점 위치를 바꾸지 못했다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("선점 위치를 바꾸지 못했다", e);
        }
    }

    /**
     * 결제하기(#449): Redis 선점을 예약으로 바꾼다. 성공하면 예약 id, 실패하면 -1이다. 결과는 created(201)·existing(200)·
     * 409 코드(소문자)·error로 남긴다.
     */
    long convertOnce(int k, long scheduleId, long userId, String holdId, long t0) {
        long start = System.nanoTime();
        String outcome;
        long reservationId = -1;
        try {
            HttpResponse<String> response = http.send(
                    request("/api/seats/schedules/" + scheduleId + "/holds/" + holdId + "/reservation", userId)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = parse(response.body());
            int status = response.statusCode();
            if (status == 201 || status == 200) {
                outcome = status == 201 ? "created" : "existing";
                reservationId = body.path("data").path("reservationId").asLong(-1);
            } else {
                outcome = status == 409
                        ? body.path("code").asText("conflict").toLowerCase(java.util.Locale.ROOT)
                        : "error";
                if (status != 409) {
                    recordError(
                            start, "convert", statusCategory(status), status, body, null, scheduleId, userId, holdId);
                }
            }
        } catch (IOException e) {
            outcome = "error";
            recordError(start, "convert", failureOf(e), 0, null, e, scheduleId, userId, holdId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "error";
            recordError(start, "convert", failureOf(e), 0, null, e, scheduleId, userId, holdId);
        }
        selloutSamples.add(new SelloutSample(k, "convert", start - t0, (System.nanoTime() - start) / 1e6, outcome));
        return reservationId;
    }

    /** 결제 포기: 선점을 해제한다. id는 예약 id 또는 holdId다. 성공하면 true. */
    boolean releaseOnce(int k, long scheduleId, long userId, String id, long t0) {
        long start = System.nanoTime();
        boolean ok;
        try {
            HttpResponse<String> response = http.send(
                    request("/api/seats/schedules/" + scheduleId + "/holds/" + id, userId)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            ok = response.statusCode() == 204;
            if (!ok) {
                int status = response.statusCode();
                String category =
                        status == 409 || status == 404 || status == 403 ? "business_reject" : statusCategory(status);
                recordError(start, "release", category, status, parse(response.body()), null, scheduleId, userId, id);
            }
        } catch (IOException e) {
            ok = false;
            recordError(start, "release", failureOf(e), 0, null, e, scheduleId, userId, id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
            recordError(start, "release", failureOf(e), 0, null, e, scheduleId, userId, id);
        }
        selloutSamples.add(new SelloutSample(
                k, "release", start - t0, (System.nanoTime() - start) / 1e6, ok ? "released" : "release_failed"));
        return ok;
    }

    /** 구역 목록을 받아 구역 순서(S01부터)대로 선택 가능 좌석 수를 돌려준다. 실패하면 null. */
    int[] readSections(int k, long scheduleId, long userId, long t0) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(
                    request("/api/seats/schedules/" + scheduleId + "/sections", userId)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode sections = parse(response.body()).path("data").path("sections");
            int[] counts = new int[SEATS / SECTION_SEATS];
            for (JsonNode section : sections) {
                int index =
                        Integer.parseInt(section.path("section").asText("S0").substring(1)) - 1;
                if (index >= 0 && index < counts.length) {
                    counts[index] = section.path("selectableCount").asInt(0);
                }
            }
            selloutSamples.add(new SelloutSample(k, "sections", start - t0, (System.nanoTime() - start) / 1e6, "ok"));
            return sections.isEmpty() ? null : counts;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 구역 하나의 좌석 상태를 받아 비어 보이는 좌석의 회차 전체 순번 목록으로 바꾼다. i번째 비트가 그 구역의 i번째 좌석(좌석 id 순)이고
     * 바이트 안 낮은 비트부터다. 구역 s의 i번째 좌석은 회차 전체에서 s × SECTION_SEATS + i번째다. 실패하면 null.
     */
    int[] readSectionView(int k, long scheduleId, long userId, int section, long t0) {
        long start = System.nanoTime();
        try {
            String name = String.format("S%02d", section + 1);
            HttpResponse<String> response = http.send(
                    request("/api/seats/schedules/" + scheduleId + "/status?section=" + name, userId)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String bitmap =
                    parse(response.body()).path("data").path("selectableBitmap").asText("");
            byte[] bits = Base64.getDecoder().decode(bitmap);
            int[] free = new int[SECTION_SEATS];
            int count = 0;
            for (int i = 0; i < SECTION_SEATS && i / 8 < bits.length; i++) {
                if ((bits[i / 8] & (1 << (i % 8))) != 0) {
                    free[count++] = section * SECTION_SEATS + i;
                }
            }
            selloutSamples.add(new SelloutSample(k, "view", start - t0, (System.nanoTime() - start) / 1e6, "ok"));
            return Arrays.copyOf(free, count);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
