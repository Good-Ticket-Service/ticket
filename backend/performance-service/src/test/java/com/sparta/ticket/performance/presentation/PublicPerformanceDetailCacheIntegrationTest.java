package com.sparta.ticket.performance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.service.PublicPerformanceService;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 공개 공연 상세 캐시(이슈 #398)를 실제 PostgreSQL·Redis로 검증한다. 데이터는 sql/public-performance-fixture.sql이다.
 * 공연 10은 판매자 501의 공개 공연(활성 회차 12-01·12-02), 13은 회차·가격 없음, 20은 초안, 22는 취소된 공개 공연이다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicPerformanceDetailCacheIntegrationTest extends PerformanceIntegrationTest {

    private static final String KEY = "performance:detail:v1:";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PublicPerformanceService service;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

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

    private ResultActions detail(long id) throws Exception {
        return mockMvc.perform(get("/api/performances/" + id));
    }

    private String body(long id) throws Exception {
        return detail(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("읽기")
    class Read {

        @Test
        @DisplayName("첫 조회는 SQL 2개로 채우고, 적중하면 SQL과 DB 연결 없이 같은 JSON을 준다")
        void hitSkipsDatabase() throws Exception {
            String first = body(10);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

            statistics.clear();
            String second = body(10);
            assertThat(statistics.getPrepareStatementCount()).isZero();
            assertThat(statistics.getConnectCount()).isZero();
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("기간 없음·가격 없음·출연 정보 없음과 취소된 공연도 캐시 값에서 그대로 복원한다")
        void restoresNullAndEmptyFields() throws Exception {
            for (long id : new long[] {13, 22}) {
                String first = body(id);
                statistics.clear();
                assertThat(body(id)).as("공연 %s", id).isEqualTo(first);
                assertThat(statistics.getPrepareStatementCount())
                        .as("공연 %s", id)
                        .isZero();
            }
        }

        @Test
        @DisplayName("초안·미존재·삭제된 공연장의 공연은 404이고 저장하지 않는다")
        void doesNotStoreNotFound() throws Exception {
            for (long id : new long[] {20, 21, 23, 999}) {
                detail(id).andExpect(status().isNotFound());
            }
            assertThat(redis.keys(KEY + "*")).isEmpty();
        }

        @Test
        @DisplayName("TTL은 저장할 때 10분에 0–60초를 더하고, 적중해도 늘리지 않는다")
        void ttlIsSetOnStoreOnly() throws Exception {
            body(10);
            Long ttl = redis.getExpire(KEY + 10, TimeUnit.SECONDS);
            assertThat(ttl).isBetween(590L, 660L);

            redis.expire(KEY + 10, Duration.ofSeconds(30));
            body(10);
            assertThat(redis.getExpire(KEY + 10, TimeUnit.SECONDS)).isLessThanOrEqualTo(30L);
        }

        @Test
        @DisplayName("만료되면 DB를 다시 읽어 새 값을 준다. 이전 값이 남아 있어도 만료 뒤에는 최신 값이다")
        void rereadsAfterExpiry() throws Exception {
            body(10);
            // 회차 등록과 겹친 조회가 이전 기간을 다시 저장한 상황(허용한 경쟁)을 만든다
            jdbc.update(
                    "UPDATE p_schedules SET start_at = '2026-12-09 19:00:00', booking_close_at = '2026-12-09 18:00:00'"
                            + " WHERE id = 2");
            detail(10).andExpect(jsonPath("$.data.period.to").value("2026-12-02"));

            redis.expire(KEY + 10, Duration.ofMillis(300));
            Thread.sleep(500);
            statistics.clear();
            detail(10).andExpect(jsonPath("$.data.period.to").value("2026-12-09"));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("읽지 못하는 캐시 값은 미적중으로 보고 DB 결과로 바꿔 저장한다")
        void replacesUnreadableValue() throws Exception {
            redis.opsForValue().set(KEY + 10, "{not-json");
            detail(10)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(10));
            assertThat(redis.opsForValue().get(KEY + 10)).startsWith("{\"id\":10");
        }
    }

    @Nested
    @DisplayName("회차 등록 무효화")
    class Register {

        private ResultActions register(String startAt, String bookingOpenAt, String bookingCloseAt) throws Exception {
            return mockMvc.perform(post("/api/seller/performances/10/schedules")
                    .header(UserHeaders.USER_ID, "501")
                    .header(UserHeaders.USERNAME, "seller501")
                    .header(UserHeaders.ROLE, "SELLER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"startAt\":\"" + startAt + "\",\"bookingOpenAt\":\"" + bookingOpenAt
                            + "\",\"bookingCloseAt\":\"" + bookingCloseAt + "\"}"));
        }

        /** fixture가 회차 ID를 직접 넣어서, 새 회차가 기존 ID와 겹치지 않게 시퀀스를 맞춘다. */
        @BeforeEach
        void alignScheduleSequence() {
            jdbc.queryForObject(
                    "SELECT setval(pg_get_serial_sequence('p_schedules', 'id'), (SELECT MAX(id) FROM p_schedules))",
                    Long.class);
        }

        @Test
        @DisplayName("등록이 커밋되면 부모 공연의 캐시를 지워 다음 조회가 새 기간을 준다")
        void evictsAfterCommit() throws Exception {
            detail(10).andExpect(jsonPath("$.data.period.to").value("2026-12-02"));
            assertThat(redis.hasKey(KEY + 10)).isTrue();

            register("2026-12-20T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-20T18:00:00+09:00")
                    .andExpect(status().isCreated());

            assertThat(redis.hasKey(KEY + 10)).isFalse();
            detail(10).andExpect(jsonPath("$.data.period.to").value("2026-12-20"));
        }

        @Test
        @DisplayName("등록이 실패하면(같은 시각 회차 409) 캐시를 지우지 않는다")
        void keepsCacheWhenRegisterFails() throws Exception {
            body(10);

            register("2026-12-01T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-01T18:00:00+09:00")
                    .andExpect(status().isConflict());

            assertThat(redis.hasKey(KEY + 10)).isTrue();
        }
    }

    @Nested
    @DisplayName("서버 내부 조회 공유")
    class InFlight {

        private double joined() {
            return meters.counter("performance.detail.load", "role", "joined").count();
        }

        @Test
        @DisplayName("같은 공연의 동시 미적중은 DB 조회 한 번을 함께 받고, 다른 공연은 기다리지 않는다")
        void sharesOneLoad() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        return invocation.callRealMethod();
                    })
                    .when(publicPerformanceReader)
                    .findDetail(10L);
            double joinedBefore = joined();
            ExecutorService pool = Executors.newFixedThreadPool(5);
            try {
                List<Future<PublicPerformanceDetail>> results = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    results.add(pool.submit(() -> service.getDetail(10L)));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                waitUntil(() -> joined() - joinedBefore >= 4);

                // 공연 10의 로더가 멈춰 있어도 공연 11은 따로 조회한다
                assertThat(service.getDetail(11L).id()).isEqualTo(11L);

                release.countDown();
                for (Future<PublicPerformanceDetail> result : results) {
                    assertThat(result.get(5, TimeUnit.SECONDS).id()).isEqualTo(10L);
                }
                verify(publicPerformanceReader, times(1)).findDetail(10L);
            } finally {
                release.countDown();
                pool.shutdownNow();
                reset(publicPerformanceReader);
            }
        }

        @Test
        @DisplayName("로더가 실패(조회 제한 시간 초과)하면 기다리던 요청도 같은 예외를 받고, 항목이 정리돼 다음 요청은 다시 조회한다")
        void sharesFailureAndCleansUp() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        throw new QueryTimeoutException("statement timeout");
                    })
                    .when(publicPerformanceReader)
                    .findDetail(10L);
            double joinedBefore = joined();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Future<PublicPerformanceDetail>> results = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    results.add(pool.submit(() -> service.getDetail(10L)));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                waitUntil(() -> joined() - joinedBefore >= 2);
                release.countDown();
                // 저장소의 예외 변환을 거친 로더의 예외 객체 하나를 모두가 그대로 받는다
                List<Throwable> causes = new ArrayList<>();
                for (Future<PublicPerformanceDetail> result : results) {
                    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .satisfies(e -> causes.add(e.getCause()));
                }
                assertThat(causes)
                        .allSatisfy(cause ->
                                assertThat(cause).isSameAs(causes.get(0)).isInstanceOf(QueryTimeoutException.class));
            } finally {
                release.countDown();
                pool.shutdownNow();
                reset(publicPerformanceReader);
            }
            assertThat(inFlight()).isZero();
            assertThat(redis.hasKey(KEY + 10)).isFalse();
            assertThat(service.getDetail(10L).id()).isEqualTo(10L);
            verify(publicPerformanceReader, times(1)).findDetail(10L);
        }

        @Test
        @DisplayName("동시 미적중의 404는 함께 받고 저장하지 않으며, 다음 요청은 DB를 다시 본다")
        void sharesNotFound() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        return invocation.callRealMethod();
                    })
                    .when(publicPerformanceReader)
                    .findDetail(20L);
            double joinedBefore = joined();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Future<PublicPerformanceDetail>> results = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    results.add(pool.submit(() -> service.getDetail(20L)));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                waitUntil(() -> joined() - joinedBefore >= 2);
                release.countDown();
                for (Future<PublicPerformanceDetail> result : results) {
                    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(BusinessException.class);
                }
                verify(publicPerformanceReader, times(1)).findDetail(20L);
            } finally {
                release.countDown();
                pool.shutdownNow();
                reset(publicPerformanceReader);
            }
            assertThat(inFlight()).isZero();
            assertThat(redis.hasKey(KEY + 20)).isFalse();
            assertThatThrownBy(() -> service.getDetail(20L)).isInstanceOf(BusinessException.class);
            verify(publicPerformanceReader, times(1)).findDetail(20L);
        }

        @Test
        @DisplayName("기다리던 요청이 취소돼도 로더는 계속하고, 나머지 요청은 결과를 받으며 로더가 끝나면 항목이 정리된다")
        void waiterCancelDoesNotCancelLoader() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        return invocation.callRealMethod();
                    })
                    .when(publicPerformanceReader)
                    .findDetail(10L);
            double joinedBefore = joined();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                Future<PublicPerformanceDetail> loader = pool.submit(() -> service.getDetail(10L));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<PublicPerformanceDetail> cancelled = pool.submit(() -> service.getDetail(10L));
                Future<PublicPerformanceDetail> waiter = pool.submit(() -> service.getDetail(10L));
                waitUntil(() -> joined() - joinedBefore >= 2);

                cancelled.cancel(true);
                assertThat(inFlight()).as("로더가 끝나기 전에는 항목이 남는다").isEqualTo(1);

                release.countDown();
                assertThat(loader.get(5, TimeUnit.SECONDS).id()).isEqualTo(10L);
                assertThat(waiter.get(5, TimeUnit.SECONDS).id()).isEqualTo(10L);
                verify(publicPerformanceReader, times(1)).findDetail(10L);
            } finally {
                release.countDown();
                pool.shutdownNow();
                reset(publicPerformanceReader);
            }
            assertThat(inFlight()).isZero();
            assertThat(redis.hasKey(KEY + 10)).isTrue();
        }

        private double inFlight() {
            return meters.get("performance.detail.load.in-flight").gauge().value();
        }

        private void waitUntil(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean()) {
                assertThat(System.nanoTime()).as("조건을 5초 안에 만족하지 않음").isLessThan(deadline);
                Thread.sleep(10);
            }
        }
    }

    @Nested
    @DisplayName("Redis 장애")
    class RedisDown {

        @Test
        @DisplayName("Redis가 멈추면 DB로 응답하고, 연속 실패 뒤에는 Redis를 건너뛰며, 복구되면 다시 캐시를 쓴다")
        void fallsBackToDatabase() throws Exception {
            var docker = REDIS.getDockerClient();
            docker.pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                for (int i = 0; i < 7; i++) {
                    detail(10)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.id").value(10));
                }
                assertThat(meters.counter("performance.detail.cache", "op", "get", "result", "skipped")
                                .count())
                        .isPositive();
            } finally {
                docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            // 건너뛰는 시간(테스트 200ms)이 지나면 다시 Redis를 쓴다
            Thread.sleep(400);
            body(10);
            body(10);
            statistics.clear();
            body(10);
            assertThat(statistics.getPrepareStatementCount()).isZero();
        }
    }
}
