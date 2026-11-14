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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #434 좌석 선점 부하 생성기. #390 통합시험 대조군과 같은 모양의 부하를 예매 서비스에 바로 보낸다(Gateway 없음).
 *
 * - 다른 좌석 선점(base): 초당 {@link #RATE}건을 고르게 보낸다. 매번 다른 좌석·다른 사용자이고, 성공하면 {@link #HOLD_MS} 뒤 해제한다.
 * - 경합 없는 burst: 매초 정각에 {@link #BURST}명이 각자 다른 좌석을 동시에 선점하고, 성공하면 같은 시간 뒤 해제한다.
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
    static final int SEATS = 2000;
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
        long scheduleId = 900_000L + pool * 100L + rep;
        List<Long> seats;
        try (Connection db = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            seats = seed(db, scheduleId);
        }
        List<Long> burstSeats = seats.subList(0, BURST);
        List<Long> baseSeats = seats.subList(BURST, seats.size());
        // 사용자는 회차마다 다른 범위를 쓴다. 같은 회차 안에서는 burst와 다른 좌석 선점이 사용자를 나눠 쓴다
        long userBase = (pool * 100L + rep) * 10_000L;

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
        String prefix = output.toString().replaceFirst("\\.json$", "");
        Thread sampler = new Thread(
                () -> load.sampleServer(t0, serverPid, jdbcUrl, jdbcUser, jdbcPassword, pgContainer, prefix));
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
        // 1초 서버 표본 원본. 멈춘 초의 서버 쪽 처리 시간·연결 대기를 타임라인과 맞춰 본다
        report.put("server_rows", new ArrayList<>(load.serverSamples));
        Files.createDirectories(output.getParent());
        JSON.writeValue(output.toFile(), report);
        System.out.println("[bench] wrote " + output);
        System.exit(0);
    }

    /** 새 회차 좌석을 만들고 id를 좌석 번호 순으로 돌려준다. 컬럼 값은 seat.sql 제약을 따른다. */
    static List<Long> seed(Connection db, long scheduleId) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,"
                        + " section, row_label, seat_number, is_sellable, status, config_version,"
                        + " created_at, created_by, updated_at, updated_by)"
                        + " SELECT ?, g, 7, 'VIP', 150000, 'A', 'R' || ((g - 1) / 25 + 1), (g - 1) % 25 + 1, true,"
                        + " 'AVAILABLE', 1, now(), 'bench', now(), 'bench' FROM generate_series(1, ?) g")) {
            insert.setLong(1, scheduleId);
            insert.setInt(2, SEATS);
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
                    case "http_server_requests_seconds_max" -> {
                        // 서버가 직접 잰 선점 요청 처리 시간(구간 최댓값). 생성기에서 본 지연과 비교한다
                        if (labels.contains("method=\"POST\"") && labels.contains("/holds")) {
                            row.merge("hold_server_max_ms", value * 1000, (a, b) -> Math.max((double) a, (double) b));
                        }
                    }
                    case "jvm_gc_pause_seconds_sum" -> gc += value;
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
}
