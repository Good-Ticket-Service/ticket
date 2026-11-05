package com.sparta.ticket.payment.infrastructure.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;

/** 시험 요청 일부의 JDBC 순서와 시간을 기록한다. 바인딩 값·PG 요청/응답·URI는 기록하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("task12")
@ConditionalOnProperty(name = "payment.diagnostics.jdbc.enabled", havingValue = "true")
public class JdbcDiagnosticConfiguration {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    static BeanPostProcessor jdbcDiagnosticProxy() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource source) return dataSource(source);
                if (bean instanceof PgClient client) {
                    return proxy(
                            PgClient.class,
                            (method, args) ->
                                    timed("pg." + method.getName(), 0, null, () -> call(client, method, args)));
                }
                return bean;
            }
        };
    }

    @Bean
    OncePerRequestFilter jdbcDiagnosticFilter() {
        return new OncePerRequestFilter() {
            private final AtomicInteger captured = new AtomicInteger();

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String key = request.getHeader("X-P13-Trace");
                String provider = request.getHeader("X-P13-Provider");
                if (!"POST".equals(request.getMethod())
                        || !request.getRequestURI().startsWith("/internal/payments")
                        || key == null
                        || !key.matches("[A-Za-z0-9_-]{1,64}")
                        || !("TOSS".equals(provider) || "KAKAO".equals(provider))
                        || captured.incrementAndGet() > 400) {
                    chain.doFilter(request, response);
                    return;
                }
                Trace trace =
                        new Trace(key, provider, request.getRequestURI().endsWith("/confirm") ? "confirm" : "checkout");
                CURRENT.set(trace);
                try {
                    chain.doFilter(request, response);
                } finally {
                    CURRENT.remove();
                    try {
                        LoggerFactory.getLogger(JdbcDiagnosticConfiguration.class)
                                .info(
                                        "P13_JDBC_TRACE {}",
                                        JSON.writeValueAsString(Map.of(
                                                "key",
                                                trace.key,
                                                "provider",
                                                trace.provider,
                                                "operation",
                                                trace.operation,
                                                "durationMs",
                                                ms(System.nanoTime() - trace.started),
                                                "httpStatus",
                                                response.getStatus(),
                                                "truncated",
                                                trace.truncated,
                                                "events",
                                                trace.events)));
                    } catch (RuntimeException | IOException ignored) {
                        // Note: 진단 로그 실패로 금융 요청의 결과를 바꾸지 않는다.
                    }
                }
            }
        };
    }

    static DataSource dataSource(DataSource source) {
        return proxy(DataSource.class, (method, args) -> {
            if (!method.getName().equals("getConnection") || CURRENT.get() == null) return call(source, method, args);
            int id = ++CURRENT.get().connections;
            Connection connection = (Connection) timed("acquire", id, null, () -> call(source, method, args));
            long acquired = System.nanoTime();
            return proxy(Connection.class, (m, a) -> {
                String name = m.getName();
                if (name.equals("prepareStatement") || name.equals("prepareCall")) {
                    String sql = normalize((String) a[0]);
                    Object statement = timed("prepare", id, sql, () -> call(connection, m, a));
                    return statement((Statement) statement, id, sql);
                }
                if (name.equals("createStatement")) return statement((Statement) call(connection, m, a), id, null);
                if (name.equals("commit") || name.equals("rollback") || name.equals("setAutoCommit")) {
                    return timed(name, id, null, () -> call(connection, m, a));
                }
                if (name.equals("close")) {
                    try {
                        return timed("close", id, null, () -> call(connection, m, a));
                    } finally {
                        if (CURRENT.get() != null) CURRENT.get().add("hold", id, null, acquired, true);
                    }
                }
                return call(connection, m, a);
            });
        });
    }

    private static Object statement(Statement statement, int id, String preparedSql) {
        Class<?> type = statement instanceof CallableStatement
                ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return proxy(type, (method, args) -> {
            String name = method.getName();
            if (name.startsWith("execute")) {
                String sql = preparedSql != null
                        ? preparedSql
                        : args != null && args.length > 0 && args[0] instanceof String
                                ? normalize((String) args[0])
                                : "BATCH";
                Object result = timed(name, id, sql, () -> call(statement, method, args));
                return result instanceof ResultSet rs ? resultSet(rs, id) : result;
            }
            Object result = call(statement, method, args);
            return result instanceof ResultSet rs ? resultSet(rs, id) : result;
        });
    }

    private static ResultSet resultSet(ResultSet result, int id) {
        return proxy(
                ResultSet.class,
                (method, args) -> method.getName().equals("next")
                        ? timed("fetchNext", id, null, () -> call(result, method, args))
                        : call(result, method, args));
    }

    private static Object timed(String operation, int id, String sql, Action action) throws Throwable {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object result = action.run();
            success = true;
            return result;
        } finally {
            if (CURRENT.get() != null) CURRENT.get().add(operation, id, sql, start, success);
        }
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> invocation.invoke(method, args)));
    }

    static String normalize(String sql) {
        return sql.replaceAll("'(?:[^']|'')*'", "?")
                .replaceAll("\\b\\d+\\b", "?")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    @FunctionalInterface
    private interface Action {
        Object run() throws Throwable;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private static final class Trace {
        private final long started = System.nanoTime();
        private final String key;
        private final String provider;
        private final String operation;
        private final List<Map<String, Object>> events = new ArrayList<>();
        private int connections;
        private boolean truncated;

        private Trace(String key, String provider, String operation) {
            this.key = key;
            this.provider = provider;
            this.operation = operation;
        }

        private void add(String operation, int id, String sql, long start, boolean success) {
            if (events.size() >= 500) {
                truncated = true;
                return;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("operation", operation);
            event.put("connection", id);
            event.put("startMs", ms(start - started));
            event.put("durationMs", ms(System.nanoTime() - start));
            event.put("success", success);
            String tx = TransactionSynchronizationManager.getCurrentTransactionName();
            if (tx != null) event.put("transaction", tx.substring(tx.lastIndexOf('.') + 1));
            if (sql != null) event.put("sql", sql);
            events.add(event);
        }
    }
}
