package com.sparta.ticket.bench.hold;

import com.sparta.ticket.reservation.ReservationServiceApplication;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.seat.application.hold.ConvertConcurrencyLimit;
import com.sparta.ticket.reservation.seat.application.hold.HoldSeatLookup;
import com.sparta.ticket.reservation.seat.application.hold.HoldStages;
import com.sparta.ticket.reservation.seat.application.hold.RedisSeatStateLoader;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldModeSwitch;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationPort;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #434 좌석 선점 처리량 측정용 예매 서비스 기동기. 운영 설정(config-repo의 application.yml·reservation-service.yml)을 파일로 읽고,
 * config-server·Eureka 없이 뜬다. DB·Kafka·Redis 주소와 기본 연결 풀 크기는 실행 스크립트가 환경 변수로 넘긴다.
 *
 * 판매 검증만 공연 서비스 대신 아래 가짜를 쓴다. #390 통합시험에서 판매 검증 서버 쪽 p99가 5~12ms였으므로 요청 스레드를
 * {@link #SALE_VALIDATION_DELAY}만큼 붙잡은 뒤 허용한다. 가짜가 바로 응답하면 스레드 점유가 실제보다 짧아 풀 크기 효과가 달라진다.
 *
 * Note: 이 클래스는 com.sparta.ticket.reservation 밖에 두어 앱의 컴포넌트 스캔에 걸리지 않는다. 가짜 빈은 기동 소스로 직접 넘긴다.
 *
 * 인자: backend 폴더 경로
 */
public final class HoldBenchServerMain {

    // 측정마다 -Dbench.sale.delay.ms로 바꾼다(모의 매진은 10ms: #390 판매 검증 서버 쪽 p99 5\~12ms의 위쪽)
    static final Duration SALE_VALIDATION_DELAY = Duration.ofMillis(Integer.getInteger("bench.sale.delay.ms", 5));

    private HoldBenchServerMain() {}

    public static void main(String[] args) {
        Path backend = Path.of(args[0]).toAbsolutePath();
        Path config = backend.resolve("config-repo");
        Path sql = backend.resolve("sql/reservation-service");
        String schema = String.join(
                ",",
                "file:" + sql.resolve("schema.sql"),
                "file:" + sql.resolve("booking.sql"),
                "file:" + sql.resolve("seat.sql"),
                "file:" + sql.resolve("coupon.sql"));
        SpringApplication app = new SpringApplication(
                ReservationServiceApplication.class,
                BenchSaleValidation.class,
                BenchHoldMode.class,
                BenchHoldModeController.class,
                BenchConnectionWait.class,
                BenchRequestTrace.class,
                BenchBookingFacade.class);
        app.setAdditionalProfiles("bench");
        app.run(
                "--spring.cloud.config.enabled=false",
                "--eureka.client.enabled=false",
                "--spring.config.additional-location=file:" + config.resolve("application.yml") + ",file:"
                        + config.resolve("reservation-service.yml"),
                // 스크립트가 측정마다 빈 스키마를 만들고, 앱이 운영과 같은 순서로 DDL을 적용한다(ddl-auto=validate)
                "--spring.sql.init.mode=always",
                "--spring.sql.init.schema-locations=" + schema,
                "--logging.level.root=WARN",
                "--logging.level.org.springframework.boot.web.embedded.tomcat=INFO");
    }

    /**
     * 기본 DataSource를 감싸 DB 연결을 얻는 시간을 선점·결제하기 단계별로 남긴다(HoldStages, #449 후속 측정). Hikari의 연결 대기 지표는
     * 풀 전체 값이라 어느 단계가 기다렸는지 알 수 없다. 연결 풀 자체는 그대로 두고 연결을 꺼내는 호출만 잰다.
     */
    @Configuration(proxyBeanMethods = false)
    static class BenchConnectionWait {

        @Bean
        static BeanPostProcessor benchConnectionWaitPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("dataSource".equals(beanName) && bean instanceof DataSource dataSource) {
                        return new TimedDataSource(dataSource);
                    }
                    return bean;
                }
            };
        }
    }

    static final class TimedDataSource extends DelegatingDataSource {

        /**
         * 요청과 PostgreSQL 세션을 잇는 SQL(PID 조회·application_name 설정·복구)을 연결마다 실행할지. 연결 대여마다 DB 왕복이 2~3번
         * 늘어 연결 대기를 부풀린다(observer effect, 2026-11-17 분석). 모드 비교 측정은 끄고(-Dbench.trace.dbSession=false), 세션
         * 추적이 필요한 진단에서만 켠다.
         */
        private static final boolean TRACE_DB_SESSION =
                Boolean.parseBoolean(System.getProperty("bench.trace.dbSession", "true"));

        TimedDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            long start = System.nanoTime();
            try {
                return track(super.getConnection());
            } finally {
                HoldStages.connectionWait(System.nanoTime() - start);
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            long start = System.nanoTime();
            try {
                return track(super.getConnection(username, password));
            } finally {
                HoldStages.connectionWait(System.nanoTime() - start);
            }
        }

        private Connection track(Connection connection) {
            String traceId = BenchRequestTrace.current();
            String previousApplicationName = null;
            int backendPid = -1;
            if (traceId != null && TRACE_DB_SESSION) {
                try (PreparedStatement statement = connection.prepareStatement(
                                "select pg_backend_pid(), current_setting('application_name')");
                        ResultSet result = statement.executeQuery()) {
                    result.next();
                    backendPid = result.getInt(1);
                    previousApplicationName = result.getString(2);
                    try (PreparedStatement set =
                            connection.prepareStatement("select set_config('application_name', ?, false)")) {
                        set.setString(1, "bench:" + traceId);
                        set.execute();
                    }
                    BenchRequestTrace.event(
                            traceId,
                            "db_connection",
                            backendPid,
                            System.nanoTime(),
                            0,
                            "application_name=bench:" + traceId);
                } catch (SQLException e) {
                    throw new IllegalStateException("벤치 요청과 PostgreSQL 세션을 연결하지 못했습니다.", e);
                }
            }
            String restoreApplicationName = previousApplicationName;
            int pid = backendPid;
            if (traceId != null) BenchRequestTrace.PID.set(pid);
            AtomicLong transactionStarted = new AtomicLong();
            HoldStages.ConnectionUsage usage = HoldStages.connectionUsageStarted();
            AtomicBoolean recorded = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        boolean close = "close".equals(method.getName()) && method.getParameterCount() == 0;
                        if (close) {
                            SQLException resetFailure = null;
                            try {
                                if (restoreApplicationName != null) {
                                    try (PreparedStatement reset = connection.prepareStatement(
                                            "select set_config('application_name', ?, false)")) {
                                        reset.setString(1, restoreApplicationName);
                                        reset.execute();
                                    }
                                }
                            } catch (SQLException e) {
                                resetFailure = e;
                            } finally {
                                try {
                                    method.invoke(connection, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                } finally {
                                    if (recorded.compareAndSet(false, true)) usage.record();
                                }
                            }
                            if (resetFailure != null) throw resetFailure;
                            return null;
                        }
                        if ("setAutoCommit".equals(method.getName())
                                && args != null
                                && args.length == 1
                                && Boolean.FALSE.equals(args[0])) {
                            long started = System.nanoTime();
                            Object result = invoke(method, connection, args);
                            long beganAt = System.nanoTime();
                            transactionStarted.set(beganAt);
                            BenchRequestTrace.event(traceId, "tx_begin", pid, beganAt, beganAt - started, "");
                            return result;
                        }
                        if ("commit".equals(method.getName()) || "rollback".equals(method.getName())) {
                            long started = System.nanoTime();
                            try {
                                return invoke(method, connection, args);
                            } finally {
                                long completed = System.nanoTime();
                                long transactionStart = transactionStarted.getAndSet(0);
                                long eventStart = transactionStart == 0 ? started : transactionStart;
                                BenchRequestTrace.event(
                                        traceId,
                                        "tx_" + method.getName(),
                                        pid,
                                        eventStart,
                                        completed - eventStart,
                                        "commit_ns=" + (completed - started));
                            }
                        }
                        if (("prepareStatement".equals(method.getName())
                                        || "prepareCall".equals(method.getName())
                                        || "createStatement".equals(method.getName()))
                                && args != null
                                && args.length > 0
                                && args[0] instanceof String sql) {
                            Object statement = invoke(method, connection, args);
                            return trackStatement(statement, method.getReturnType(), traceId, pid, sql);
                        }
                        return invoke(method, connection, args);
                    });
        }

        private static Object trackStatement(Object statement, Class<?> contract, String traceId, int pid, String sql) {
            String shape = normalizeSql(sql);
            return Proxy.newProxyInstance(
                    contract.getClassLoader(), new Class<?>[] {contract}, (proxy, method, args) -> {
                        if (method.getName().startsWith("execute")) {
                            long started = System.nanoTime();
                            try {
                                return invoke(method, statement, args);
                            } finally {
                                BenchRequestTrace.event(
                                        traceId, "sql", pid, started, System.nanoTime() - started, shape);
                            }
                        }
                        return invoke(method, statement, args);
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static final Pattern SQL_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\r\\n]*");
        private static final Pattern SQL_STRING = Pattern.compile("'(?:''|[^'])*'");
        private static final Pattern SQL_NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
        private static final Pattern SQL_SPACE = Pattern.compile("\\s+");

        /** SQL 문장별 정규화 결과. Hibernate가 만드는 문장은 종류가 적어 상한에 닿지 않는다. 넘으면 저장하지 않고 계산만 한다. */
        private static final Map<String, String> SQL_SHAPES = new ConcurrentHashMap<>();

        private static final int SQL_SHAPES_MAX = 10_000;

        /**
         * Note: 예전에는 문장마다 replaceAll 4번(패턴도 매번 컴파일)을 해 측정 서버 CPU의 약 1/4을 썼다(2026-11-17 JFR). 모드마다 SQL
         * 수가 달라 비교를 흔들었다. 패턴을 한 번만 컴파일하고 같은 문장은 저장한 결과를 쓴다.
         */
        private static String normalizeSql(String sql) {
            String cached = SQL_SHAPES.get(sql);
            if (cached != null) {
                return cached;
            }
            String shape = SQL_SPACE
                    .matcher(SQL_NUMBER
                            .matcher(SQL_STRING
                                    .matcher(SQL_COMMENT.matcher(sql).replaceAll(" "))
                                    .replaceAll("?"))
                            .replaceAll("?"))
                    .replaceAll(" ")
                    .trim();
            if (SQL_SHAPES.size() < SQL_SHAPES_MAX) {
                SQL_SHAPES.put(sql, shape);
            }
            return shape;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BenchRequestTrace {
        private static final String CONTAINER_ENTERED_NANOS = "bench.connector.entered.nanos";
        private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
        private static final ThreadLocal<Long> START = new ThreadLocal<>();
        private static final ThreadLocal<Integer> PID = new ThreadLocal<>();
        private static final ThreadLocal<StringBuilder> EVENTS = new ThreadLocal<>();
        private static final long DETAIL_THRESHOLD_NANOS =
                TimeUnit.MILLISECONDS.toNanos(Long.getLong("bench.trace.minServerMs", 500L));

        static String current() {
            return TRACE.get();
        }

        static void event(String traceId, String event, int pid, long started, long elapsed, String detail) {
            if (traceId != null) {
                long requestStarted = START.get() == null ? started : START.get();
                StringBuilder buffer = EVENTS.get();
                if (buffer != null) {
                    buffer.append("[bench-db-event] trace=")
                            .append(traceId)
                            .append(" pid=")
                            .append(pid)
                            .append(" event=")
                            .append(event)
                            .append(" offset_ns=")
                            .append(started - requestStarted)
                            .append(" elapsed_ns=")
                            .append(elapsed)
                            .append(" sql=")
                            .append(detail)
                            .append('\n');
                }
            }
        }

        static void bookingCall(String method, long started, long elapsed) {
            String traceId = TRACE.get();
            if (traceId != null) {
                event(traceId, "app_booking_" + method, PID.get() == null ? -1 : PID.get(), started, elapsed, "");
            }
        }

        @Bean(destroyMethod = "close")
        BenchTraceLogWriter benchTraceLogWriter() {
            return new BenchTraceLogWriter();
        }

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> benchConnectorEntryValve() {
            return factory -> factory.addEngineValves(new ValveBase() {
                @Override
                public void invoke(Request request, Response response) throws IOException, ServletException {
                    String traceId = request.getHeader("X-Bench-Trace-Id");
                    if (traceId != null && traceId.matches("convert-[a-z0-9-]{1,80}")) {
                        request.setAttribute(CONTAINER_ENTERED_NANOS, System.nanoTime());
                    }
                    getNext().invoke(request, response);
                }
            });
        }

        @Bean
        Filter benchTraceFilter(BenchTraceLogWriter writer) {
            return (request, response, chain) -> {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                String traceId = httpRequest.getHeader("X-Bench-Trace-Id");
                if (traceId == null || !traceId.matches("convert-[a-z0-9-]{1,80}")) {
                    chain.doFilter(request, response);
                    return;
                }
                long started = System.nanoTime();
                long sentEpochMs = parseEpochMillis(httpRequest.getHeader("X-Bench-Sent-Epoch-Ms"));
                long preFilterMs = sentEpochMs < 0 ? -1 : Math.max(0, System.currentTimeMillis() - sentEpochMs);
                Object containerEntered = httpRequest.getAttribute(CONTAINER_ENTERED_NANOS);
                Double connectorToFilterMs =
                        containerEntered instanceof Long entered ? Math.max(0, started - entered) / 1e6 : null;
                TRACE.set(traceId);
                START.set(started);
                PID.remove();
                EVENTS.set(new StringBuilder());
                try {
                    ((HttpServletResponse) response).setHeader("X-Bench-Trace-Id", traceId);
                    if (preFilterMs >= 0) {
                        ((HttpServletResponse) response).setHeader("X-Bench-Pre-Filter-Ms", Long.toString(preFilterMs));
                    }
                    if (connectorToFilterMs != null) {
                        ((HttpServletResponse) response)
                                .setHeader("X-Bench-Connector-To-Filter-Ms", Double.toString(connectorToFilterMs));
                    }
                    chain.doFilter(request, response);
                } finally {
                    long elapsed = System.nanoTime() - started;
                    StringBuilder events = EVENTS.get();
                    if (events != null && elapsed >= DETAIL_THRESHOLD_NANOS) writer.enqueue(events.toString());
                    writer.enqueue(String.format(
                            Locale.ROOT,
                            "[bench-request] trace=%s pre_filter_ms=%d connector_to_filter_ms=%s server_ms=%.3f%n",
                            traceId,
                            preFilterMs,
                            connectorToFilterMs == null
                                    ? "missing"
                                    : String.format(Locale.ROOT, "%.3f", connectorToFilterMs),
                            elapsed / 1_000_000.0));
                    TRACE.remove();
                    START.remove();
                    PID.remove();
                    EVENTS.remove();
                }
            };
        }

        private static long parseEpochMillis(String value) {
            if (value == null || !value.matches("\\d{1,16}")) return -1;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /** 요청 스레드는 bounded queue에만 기록하고, 파일 출력은 전용 스레드가 담당한다. */
    static final class BenchTraceLogWriter implements AutoCloseable {
        private static final int CAPACITY = Integer.getInteger("bench.trace.log.queue.capacity", 16_384);
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong dropped = new AtomicLong();
        private final Thread worker;

        BenchTraceLogWriter() {
            worker = new Thread(this::writeLoop, "bench-trace-log-writer");
            worker.setDaemon(true);
            worker.start();
        }

        void enqueue(String line) {
            if (!queue.offer(line)) dropped.incrementAndGet();
        }

        private void writeLoop() {
            while (running.get() || !queue.isEmpty()) {
                try {
                    String line = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (line != null) System.err.print(line);
                } catch (InterruptedException e) {
                    if (running.get()) Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                worker.join(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) dropped.addAndGet(queue.size());
            System.err.printf(Locale.ROOT, "[bench-trace-log] dropped=%d%n", dropped.get());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BenchBookingFacade {
        @Bean
        static BeanPostProcessor benchBookingFacadePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof BookingFacade facade)) return bean;
                    return Proxy.newProxyInstance(
                            BookingFacade.class.getClassLoader(),
                            new Class<?>[] {BookingFacade.class},
                            (proxy, method, args) -> {
                                long started = System.nanoTime();
                                try {
                                    return method.invoke(facade, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                } finally {
                                    BenchRequestTrace.bookingCall(
                                            method.getName(), started, System.nanoTime() - started);
                                }
                            });
                }
            };
        }
    }

    /** 공연 서비스 판매 검증 대신 쓰는 가짜. 지연 뒤 요청 좌석을 모두 허용한다. */
    @Configuration(proxyBeanMethods = false)
    static class BenchSaleValidation {

        @Bean
        @Primary
        SaleValidationPort benchSaleValidationPort() {
            return (scheduleId, configVersion, venueSeatIds) -> {
                LockSupport.parkNanos(SALE_VALIDATION_DELAY.toNanos());
                List<SaleValidationSeat> seats = venueSeatIds.stream()
                        .map(id -> new SaleValidationSeat(id, 7L, "VIP", 150_000))
                        .toList();
                return new SaleValidation(
                        true,
                        null,
                        scheduleId,
                        11L,
                        1001L,
                        configVersion,
                        1L,
                        LocalDateTime.of(2026, 12, 31, 19, 0),
                        "측정 공연",
                        "MUSICAL",
                        seats);
            };
        }
    }

    /**
     * 선점 위치 스위치(#449)를 측정 중에 바꿀 수 있게 한다. 운영 스위치는 기동 때 정해져, 모드마다 서버를 다시 띄우면 JVM 예열 상태가
     * 달라 두 모드 비교가 흔들린다. 같은 서버에서 회차마다 모드를 바꿔 번갈아 잰다. 기본값은 기동 설정(reservation.seat.hold.mode)이다.
     */
    @Configuration(proxyBeanMethods = false)
    static class BenchHoldMode {

        @Bean
        @Primary
        BenchHoldModeSwitch benchHoldModeSwitch(SeatHoldProperties properties) {
            return new BenchHoldModeSwitch(properties);
        }
    }

    static class BenchHoldModeSwitch extends SeatHoldModeSwitch {

        private volatile SeatHoldMode mode;
        private volatile boolean convertInOneTransaction;
        private volatile boolean redisSeatState;

        BenchHoldModeSwitch(SeatHoldProperties properties) {
            super(properties);
            this.mode = properties.mode();
        }

        @Override
        public SeatHoldMode current() {
            return mode;
        }

        @Override
        public boolean convertInOneTransaction() {
            return convertInOneTransaction;
        }

        @Override
        public boolean redisSeatState() {
            return redisSeatState;
        }

        void set(SeatHoldMode mode, boolean convertInOneTransaction, boolean redisSeatState) {
            this.mode = mode;
            this.convertInOneTransaction = convertInOneTransaction;
            this.redisSeatState = redisSeatState;
        }
    }

    /** 부하 생성기가 회차를 시작하기 전에 부른다. 측정 서버에만 있다. */
    @RestController
    static class BenchHoldModeController {

        private final BenchHoldModeSwitch modeSwitch;
        private final RedisSeatStateLoader stateLoader;
        private final HoldSeatLookup holdSeats;
        private final ConvertConcurrencyLimit convertLimit;

        /**
         * "-SL" 모드에서 쓰는 좌석 선택 완료 동시 처리 상한. 기본 60 = 연결 풀 30 × (좌석 선택 완료 약 80ms ÷ 그중 DB 약 40ms)
         * (ConvertLimitProperties 기준). 30으로 쟀을 때 평상시 처리량보다 작아 재시도가 몰렸다.
         */
        private static final int BENCH_CONVERT_LIMIT = Integer.getInteger("bench.convert.limit", 60);

        BenchHoldModeController(
                BenchHoldModeSwitch modeSwitch,
                RedisSeatStateLoader stateLoader,
                HoldSeatLookup holdSeats,
                ConvertConcurrencyLimit convertLimit) {
            this.modeSwitch = modeSwitch;
            this.stateLoader = stateLoader;
            this.holdSeats = holdSeats;
            this.convertLimit = convertLimit;
        }

        /**
         * mode는 DB, REDIS, REDIS1C(REDIS + 결제하기 한 트랜잭션, #449 후속 1-C)다. 끝에 "-S"를 붙이면 Redis 점유 판정(#449 후속)을
         * 켠다(REDIS-S, REDIS1C-S). "-SL"은 여기에 좌석 선택 완료 동시 처리 상한을 더한다(REDIS-SL).
         */
        @PostMapping("/bench/hold-mode")
        String set(@RequestParam String mode) {
            // "-SL"은 Redis 점유 판정 + 좌석 선택 완료 동시 처리 상한(bench.convert.limit)이다(#449 후속)
            boolean limited = mode.endsWith("-SL");
            convertLimit.setLimit(limited ? BENCH_CONVERT_LIMIT : 0);
            boolean state = limited || mode.endsWith("-S");
            String base = limited
                    ? mode.substring(0, mode.length() - 3)
                    : state ? mode.substring(0, mode.length() - 2) : mode;
            boolean oneTx = base.endsWith("1C");
            modeSwitch.set(SeatHoldMode.valueOf(oneTx ? base.substring(0, base.length() - 2) : base), oneTx, state);
            return mode;
        }

        /**
         * 회차의 DB 점유 좌석을 지우고 다시 올리고, 선점용 좌석 고정 정보를 읽어 둔다. 부하 생성기가 회차를 만든 뒤, 부하를 보내기 전에
         * 부른다. 첫 선점이 회차 좌석 전체를 읽느라 측정 첫 구간이 늦어지지 않게 한다.
         */
        @PostMapping("/bench/seat-state/reload")
        String reload(@RequestParam Long scheduleId) {
            stateLoader.reload(scheduleId);
            holdSeats.invalidate(scheduleId);
            holdSeats.warm(scheduleId);
            return "ok";
        }
    }
}
