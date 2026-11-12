package com.sparta.ticket.payment.application.service.payment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 측정 JVM에서만 실제 JDBC 실행을 단계별로 센다. SQL 본문과 바인딩 값은 수집하지 않는다. */
@TestConfiguration
public class ApprovalWaitProbe {
    private static final ThreadLocal<Counters> CURRENT = new ThreadLocal<>();
    private static final Map<String, Counters> STAGES = new ConcurrentHashMap<>();
    private static volatile boolean measuring;

    static void start() {
        STAGES.clear();
        measuring = true;
    }

    static Map<String, Object> finish() {
        measuring = false;
        var report = new LinkedHashMap<String, Object>();
        STAGES.forEach((name, counters) -> report.put(name, counters.snapshot()));
        return report;
    }

    @Bean
    static BeanPostProcessor approvalWaitJdbcProbe() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if ((bean instanceof MessageApprovalTransactions || bean instanceof PaymentTransactions)
                        && bean instanceof Advised advised) {
                    advised.addAdvice(0, (MethodInterceptor) invocation -> {
                        if (!measuring) return invocation.proceed();
                        if (bean instanceof PaymentTransactions
                                && !invocation.getMethod().getName().equals("complete")) return invocation.proceed();
                        var counters =
                                STAGES.computeIfAbsent(invocation.getMethod().getName(), key -> new Counters());
                        var previous = CURRENT.get();
                        CURRENT.set(counters);
                        counters.calls.increment();
                        long started = System.nanoTime();
                        try {
                            return invocation.proceed();
                        } finally {
                            counters.elapsedNanos.add(System.nanoTime() - started);
                            if (previous == null) CURRENT.remove();
                            else CURRENT.set(previous);
                        }
                    });
                }
                if (!(bean instanceof DataSource source)) return bean;
                return Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                            long started = System.nanoTime();
                            Object result = call(source, method, args);
                            if (result instanceof Connection connection) {
                                var counters = CURRENT.get();
                                if (counters != null) counters.acquireNanos.add(System.nanoTime() - started);
                                return connection(connection);
                            }
                            return result;
                        });
            }
        };
    }

    private static Connection connection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = call(delegate, method, args);
                    var counters = CURRENT.get();
                    if (counters != null && method.getName().equals("commit")) counters.commits.increment();
                    if (counters != null && method.getName().equals("rollback")) counters.rollbacks.increment();
                    if (result instanceof Statement statement) {
                        String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
                        return statement(statement, sql);
                    }
                    return result;
                });
    }

    private static Statement statement(Statement delegate, String sql) {
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    var counters = CURRENT.get();
                    if (counters == null || !method.getName().startsWith("execute"))
                        return call(delegate, method, args);
                    counters.sql.increment();
                    String normalized = sql.toLowerCase(java.util.Locale.ROOT);
                    if (normalized.contains("p_payments") && normalized.contains("for "))
                        counters.lockQueries.increment();
                    long started = System.nanoTime();
                    try {
                        return call(delegate, method, args);
                    } finally {
                        counters.sqlNanos.add(System.nanoTime() - started);
                    }
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static final class Counters {
        final LongAdder calls = new LongAdder();
        final LongAdder sql = new LongAdder();
        final LongAdder lockQueries = new LongAdder();
        final LongAdder commits = new LongAdder();
        final LongAdder rollbacks = new LongAdder();
        final LongAdder elapsedNanos = new LongAdder();
        final LongAdder sqlNanos = new LongAdder();
        final LongAdder acquireNanos = new LongAdder();

        Map<String, Object> snapshot() {
            return Map.of(
                    "calls",
                    calls.sum(),
                    "sqlExecutions",
                    sql.sum(),
                    "paymentLockQueries",
                    lockQueries.sum(),
                    "commits",
                    commits.sum(),
                    "rollbacks",
                    rollbacks.sum(),
                    "elapsedMillis",
                    elapsedNanos.sum() / 1_000_000.0,
                    "sqlMillis",
                    sqlNanos.sum() / 1_000_000.0,
                    "acquireMillis",
                    acquireNanos.sum() / 1_000_000.0);
        }
    }
}
