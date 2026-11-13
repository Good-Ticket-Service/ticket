package com.sparta.ticket.performance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.PublicSchedule;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.application.service.AdminScheduleService;
import com.sparta.ticket.performance.application.service.PublicScheduleService;
import com.sparta.ticket.performance.application.service.SaleValidationService;
import com.sparta.ticket.performance.application.service.SeatSetupAttempts;
import com.sparta.ticket.performance.domain.schedule.SaleRejectReason;
import com.sparta.ticket.performance.domain.schedule.SalesStatus;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 회차 목록 캐시를 별도 Testcontainers PostgreSQL·Redis로 검증한다. 기존 측정 자원은 사용하지 않는다. */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql", "/sql/public-schedule-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicScheduleListCacheIntegrationTest extends PerformanceIntegrationTest {
    private static final String KEY = "performance:schedule:list:v1:";

    @Autowired
    private PublicScheduleService service;

    @Autowired
    private AdminScheduleService admin;

    @Autowired
    private SaleValidationService saleValidation;

    @Autowired
    private ScheduleStore store;

    @Autowired
    private SeatSetupAttempts attempts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
        statistics.clear();
    }

    private String body(long id) throws Exception {
        return mockMvc.perform(get("/api/performances/" + id + "/schedules"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    @DisplayName("공개 공연의 회차·빈 목록·준비 중·취소 공연을 저장하고 적중은 같은 JSON과 SQL·연결 0회다")
    void hitSkipsDatabase() throws Exception {
        for (long id : new long[] {10, 12, 13, 22}) {
            statistics.clear();
            String first = body(id);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(redis.hasKey(KEY + id)).isTrue();
            statistics.clear();
            assertThat(body(id)).isEqualTo(first);
            assertThat(statistics.getPrepareStatementCount()).isZero();
            assertThat(statistics.getConnectCount()).isZero();
        }
    }

    @Test
    @DisplayName("404는 저장하지 않아 공개된 뒤 바로 조회할 수 있다")
    void notFoundIsNotCached() throws Exception {
        for (long id : new long[] {20, 21, 23, 999}) {
            mockMvc.perform(get("/api/performances/" + id + "/schedules")).andExpect(status().isNotFound());
            assertThat(redis.hasKey(KEY + id)).isFalse();
        }
        jdbc.update("UPDATE p_performances SET publication_status='PUBLISHED', published_at=? WHERE id=20", NOW);
        body(20);
        assertThat(redis.hasKey(KEY + 20)).isTrue();
    }

    @Test
    @DisplayName("동일한 저장값으로 오픈·마감 정각을 요청 시각으로 계산하고 취소 우선순위를 유지한다")
    void computesAtRequestTime() {
        clock.set(LocalDateTime.of(2026, 11, 10, 13, 59, 59));
        assertThat(service.getSchedules(10L))
                .extracting(PublicSchedule::salesStatus)
                .containsExactly(SalesStatus.CANCELED, SalesStatus.BEFORE_OPEN, SalesStatus.BEFORE_OPEN);
        String value = redis.opsForValue().get(KEY + 10);
        statistics.clear();
        clock.set(LocalDateTime.of(2026, 11, 10, 14, 0));
        assertThat(service.getSchedules(10L))
                .extracting(PublicSchedule::salesStatus)
                .containsExactly(SalesStatus.CANCELED, SalesStatus.ON_SALE, SalesStatus.ON_SALE);
        clock.set(LocalDateTime.of(2026, 12, 1, 18, 0));
        assertThat(service.getSchedules(10L))
                .extracting(PublicSchedule::salesStatus)
                .containsExactly(SalesStatus.CANCELED, SalesStatus.CLOSED, SalesStatus.ON_SALE);
        assertThat(statistics.getPrepareStatementCount()).isZero();
        assertThat(redis.opsForValue().get(KEY + 10)).isEqualTo(value);
    }

    @Test
    @DisplayName("목록에 이전 값이 남아 있어도 회차 단건과 판매 검증은 현재 DB의 판매 중지를 읽는다")
    void singleAndValidationDoNotUseListCache() {
        clock.set(LocalDateTime.of(2026, 11, 10, 14, 0));
        assertThat(service.getSchedules(10L).get(1).salesStatus()).isEqualTo(SalesStatus.ON_SALE);
        jdbc.update("UPDATE p_schedules SET sales_suspended=true, suspension_reason='점검' WHERE id=1");
        assertThat(service.getSchedules(10L).get(1).salesStatus()).isEqualTo(SalesStatus.ON_SALE);
        statistics.clear();
        assertThat(service.getSchedule(1L).salesStatus()).isEqualTo(SalesStatus.UNAVAILABLE);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        var validation = saleValidation.validate(1L, 1L, List.of(1L));
        assertThat(validation.allowed()).isFalse();
        assertThat(validation.reasonCode()).isEqualTo(SaleRejectReason.SALES_SUSPENDED);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL은 60–66초이며 적중으로 연장하지 않고 만료 뒤 DB를 다시 읽는다")
    void expiresWithoutRenewal() throws Exception {
        body(10);
        assertThat(redis.getExpire(KEY + 10, TimeUnit.MILLISECONDS)).isBetween(58000L, 66000L);
        jdbc.update("UPDATE p_schedules SET sales_suspended=true, suspension_reason='점검' WHERE id=1");
        redis.expire(KEY + 10, Duration.ofMillis(300));
        assertThat(service.getSchedules(10L).get(1).salesSuspended()).isFalse();
        assertThat(redis.getExpire(KEY + 10, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(300L);
        Thread.sleep(500);
        statistics.clear();
        assertThat(service.getSchedules(10L).get(1).salesSuspended()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("형식·필수 값·공연 소속이 잘못된 값은 지우고 DB에서 다시 채운다")
    void replacesInvalidValues() throws Exception {
        for (String invalid : List.of(
                "{broken",
                "null",
                "{}",
                "{\"performanceStatus\":\"ACTIVE\",\"schedules\":null}",
                "{\"performanceStatus\":\"ACTIVE\",\"schedules\":[null]}",
                "{\"performanceStatus\":\"ACTIVE\",\"schedules\":[{\"id\":1,\"performanceId\":11}]}")) {
            redis.opsForValue().set(KEY + 10, invalid);
            statistics.clear();
            body(10);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(redis.opsForValue().get(KEY + 10)).contains("\"performanceStatus\":\"ACTIVE\"");
        }
    }

    @Test
    @DisplayName("회차 등록 커밋 뒤 좌석 호출 전에 삭제하고, 중복 등록 롤백에는 캐시를 유지한다")
    void registrationEvictsBeforeDispatch() throws Exception {
        jdbc.queryForObject(
                "SELECT setval(pg_get_serial_sequence('p_schedules','id'), (SELECT MAX(id) FROM p_schedules))",
                Long.class);
        body(10);
        seatSetupClient.answer(command -> {
            assertThat(redis.hasKey(KEY + 10)).isFalse();
            return com.sparta.ticket.performance.support.FakeSeatSetupClient.ready(command);
        });
        String input = "{\"startAt\":\"2026-12-20T19:00:00+09:00\",\"bookingOpenAt\":\"2026-11-10T14:00:00+09:00\","
                + "\"bookingCloseAt\":\"2026-12-20T18:00:00+09:00\"}";
        register(input, 201);
        assertThat(redis.hasKey(KEY + 10)).isFalse();
        assertThat(service.getSchedules(10L)).hasSize(4);
        register(input, 409);
        assertThat(redis.hasKey(KEY + 10)).isTrue();
    }

    private void register(String input, int expected) throws Exception {
        mockMvc.perform(post("/api/seller/performances/10/schedules")
                        .header(UserHeaders.USER_ID, "501")
                        .header(UserHeaders.USERNAME, "seller501")
                        .header(UserHeaders.ROLE, "SELLER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(input))
                .andExpect(status().is(expected));
    }

    @Test
    @DisplayName("판매 중지 저장은 실제 커밋 뒤 삭제하며 롤백·무변경은 유지하고 사유 변경·재개도 삭제한다")
    void suspensionFollowsCommit() throws Exception {
        body(10);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            admin.changeSalesSuspension(1L, 0, true, "점검");
            assertThat(redis.hasKey(KEY + 10)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(redis.hasKey(KEY + 10)).isTrue();
        tx.executeWithoutResult(status -> {
            admin.changeSalesSuspension(1L, 0, true, "점검");
            assertThat(redis.hasKey(KEY + 10)).isTrue();
        });
        assertThat(redis.hasKey(KEY + 10)).isFalse();
        body(10);
        admin.changeSalesSuspension(1L, 1, true, "점검");
        assertThat(redis.hasKey(KEY + 10)).isTrue();
        admin.changeSalesSuspension(1L, 1, true, "사유 변경");
        assertThat(redis.hasKey(KEY + 10)).isFalse();
        body(10);
        admin.changeSalesSuspension(1L, 2, false, null);
        assertThat(redis.hasKey(KEY + 10)).isFalse();
    }

    @Test
    @DisplayName("좌석 준비 저장은 시도 정보만 바뀌어도 커밋 뒤 삭제하고 롤백에는 유지한다")
    void seatSetupFollowsCommit() throws Exception {
        body(12);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            var schedule = store.findByIdForUpdate(12L).orElseThrow();
            store.saveSeatSetup(schedule);
            assertThat(redis.hasKey(KEY + 12)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(redis.hasKey(KEY + 12)).isTrue();
        tx.executeWithoutResult(status -> {
            store.saveSeatSetup(store.findByIdForUpdate(12L).orElseThrow());
            assertThat(redis.hasKey(KEY + 12)).isTrue();
        });
        assertThat(redis.hasKey(KEY + 12)).isFalse();
    }

    @Test
    @DisplayName("같은 트랜잭션에서 여러 회차를 저장해도 공연별로 한 번 지우며 롤백에는 지우지 않는다")
    void deduplicatesPerPerformanceInTransaction() throws Exception {
        body(10);
        body(12);
        double before = evictionCount();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            for (long id : new long[] {1, 6, 12}) {
                store.saveSeatSetup(store.findByIdForUpdate(id).orElseThrow());
            }
            assertThat(redis.hasKey(KEY + 10)).isTrue();
            assertThat(redis.hasKey(KEY + 12)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(evictionCount()).isEqualTo(before);
        tx.executeWithoutResult(status -> {
            for (long id : new long[] {1, 6, 12}) {
                store.saveSeatSetup(store.findByIdForUpdate(id).orElseThrow());
            }
        });
        assertThat(evictionCount() - before).isEqualTo(2);
        assertThat(redis.hasKey(KEY + 10)).isFalse();
        assertThat(redis.hasKey(KEY + 12)).isFalse();
    }

    @Test
    @DisplayName("같은 공연도 REQUIRES_NEW 안팎의 커밋은 각각 한 번 지운다")
    void deduplicationDoesNotCrossTransactions() throws Exception {
        String original = body(12);
        String cached = redis.opsForValue().get(KEY + 12);
        double before = evictionCount();
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            store.saveSeatSetup(store.findByIdForUpdate(6L).orElseThrow());
            inner.executeWithoutResult(innerStatus -> {
                store.saveSeatSetup(store.findByIdForUpdate(12L).orElseThrow());
                assertThat(redis.hasKey(KEY + 12)).isTrue();
            });
            assertThat(evictionCount() - before).isEqualTo(1);
            assertThat(redis.hasKey(KEY + 12)).isFalse();
            // 안쪽 삭제 뒤 다시 채워진 값도 바깥 커밋이 독립적으로 지워야 한다.
            redis.opsForValue().set(KEY + 12, cached, Duration.ofMinutes(1));
        });
        assertThat(evictionCount() - before).isEqualTo(2);
        assertThat(redis.hasKey(KEY + 12)).isFalse();
        assertThat(body(12)).isEqualTo(original);
    }

    private double evictionCount() {
        return meters.counter("performance.schedule.list.cache", "op", "evict", "result", "ok")
                .count();
    }

    @Test
    @DisplayName("REQUIRES_NEW 좌석 준비의 삭제는 바깥 트랜잭션 롤백과 무관하게 안쪽 커밋에 따른다")
    void innerCommitEvictsIndependently() throws Exception {
        body(12);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            attempts.start(12L);
            assertThat(redis.hasKey(KEY + 12)).isFalse();
            status.setRollbackOnly();
        });
        assertThat(redis.hasKey(KEY + 12)).isFalse();
    }

    @Test
    @DisplayName("동시 미적중은 로더 한 번만 실행하고 다른 공연은 독립적이며 대기 뒤의 시각으로 상태를 계산한다")
    void sharesLoadAndUsesTimeAfterWait() throws Exception {
        clock.set(LocalDateTime.of(2026, 11, 10, 13, 59, 59));
        runConcurrent(10L, false, () -> {
            assertThat(service.getSchedules(13L)).isEmpty();
            clock.set(LocalDateTime.of(2026, 11, 10, 14, 0));
        });
        verify(publicScheduleReader, times(1)).findSchedules(10L);
        assertThat(service.getSchedules(10L).get(1).salesStatus()).isEqualTo(SalesStatus.ON_SALE);
    }

    @Test
    @DisplayName("동시 404·DB 예외를 공유한 뒤 진행 항목을 정리하고 다음 요청은 새로 조회한다")
    void cleansUpNotFoundAndFailure() throws Exception {
        runConcurrent(20L, false, () -> {});
        assertThat(redis.hasKey(KEY + 20)).isFalse();
        assertThatThrownBy(() -> service.getSchedules(20L)).isInstanceOf(BusinessException.class);
        runConcurrent(10L, true, () -> {});
        assertThat(redis.hasKey(KEY + 10)).isFalse();
        reset(publicScheduleReader);
        assertThat(service.getSchedules(10L)).hasSize(3);
    }

    private void runConcurrent(long id, boolean fail, Runnable beforeRelease) throws Exception {
        reset(publicScheduleReader);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    if (fail) {
                        throw new QueryTimeoutException("statement timeout");
                    }
                    return invocation.callRealMethod();
                })
                .when(publicScheduleReader)
                .findPublicPerformanceStatus(id);
        double before = meters.counter("performance.schedule.list.load", "role", "joined")
                .count();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<PublicSchedule>>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> service.getSchedules(id)));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> meters.counter("performance.schedule.list.load", "role", "joined")
                                    .count()
                            - before
                    >= 3);
            assertThat(meters.get("performance.schedule.list.load.in-flight")
                            .gauge()
                            .value())
                    .isEqualTo(1);
            beforeRelease.run();
            release.countDown();
            for (var result : results) {
                if (fail || id == 20) {
                    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseInstanceOf(fail ? QueryTimeoutException.class : BusinessException.class);
                } else {
                    assertThat(result.get(5, TimeUnit.SECONDS).get(1).salesStatus())
                            .isEqualTo(SalesStatus.ON_SALE);
                }
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(meters.get("performance.schedule.list.load.in-flight")
                        .gauge()
                        .value())
                .isZero();
        verify(publicScheduleReader, times(1)).findPublicPerformanceStatus(id);
    }

    @Test
    @DisplayName("변경 커밋 뒤 이전 조회가 늦게 저장되는 경쟁은 저장 시점 TTL로 만료한다")
    void lateOldLoadExpires() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    Object rows = invocation.callRealMethod();
                    read.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return rows;
                })
                .when(publicScheduleReader)
                .findSchedules(10L);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<PublicSchedule>> old = pool.submit(() -> service.getSchedules(10L));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            admin.changeSalesSuspension(1L, 0, true, "점검");
            assertThat(redis.hasKey(KEY + 10)).isFalse();
            release.countDown();
            assertThat(old.get(5, TimeUnit.SECONDS).get(1).salesSuspended()).isFalse();
            assertThat(service.getSchedules(10L).get(1).salesSuspended()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
            reset(publicScheduleReader);
        }
        redis.expire(KEY + 10, Duration.ofMillis(200));
        Thread.sleep(400);
        assertThat(service.getSchedules(10L).get(1).salesSuspended()).isTrue();
    }

    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("Redis 제한 시간 초과·일시 우회 중에도 DB로 응답하고 복구 후 캐시를 다시 사용한다")
    void redisFailureFallsBack() throws Exception {
        var docker = REDIS.getDockerClient();
        double skipped = meters.counter("performance.schedule.list.cache", "op", "get", "result", "skipped")
                .count();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (int i = 0; i < 7; i++) {
                body(10);
            }
            assertThat(meters.counter("performance.schedule.list.cache", "op", "get", "result", "skipped")
                            .count())
                    .isGreaterThan(skipped);
            // 커밋 뒤 DEL 실패도 명령 성공을 바꾸지 않는다.
            admin.changeSalesSuspension(1L, 0, true, "점검");
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        Thread.sleep(400);
        body(10);
        body(10);
        statistics.clear();
        body(10);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }
}
