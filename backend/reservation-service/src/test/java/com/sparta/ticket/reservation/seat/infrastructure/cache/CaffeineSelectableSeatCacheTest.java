package com.sparta.ticket.reservation.seat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.SeatMapSnapshotLoader;
import com.sparta.ticket.reservation.seat.application.SeatSnapshotMetrics;
import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import com.sparta.ticket.reservation.seat.application.SelectableSeatQueryService;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.support.DeferredTasks;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 좌석 배치도 캐시를 Spring 없이 확인한다(#311). refresh(기존 값을 주고 뒤에서 다시 만들기), 최대 보관 시간, 동시 요청의 조회 횟수,
 * 실패한 조회, executor 거절, 좌석 수 기준 상한을 본다.
 */
class CaffeineSelectableSeatCacheTest {

    private static final Duration REFRESH = Duration.ofSeconds(1);
    // Caffeine은 refresh 시간을 "넘어야" refresh를 시작하고(>), 만료는 최대 보관 시간에 "닿으면" 한다(>=)
    private static final Duration PAST_REFRESH = REFRESH.plusMillis(1);
    private static final Duration MAX_AGE = Duration.ofSeconds(5);
    private static final SelectableSeatProperties PROPERTIES = new SelectableSeatProperties(REFRESH, MAX_AGE);

    private final MutableTicker ticker = new MutableTicker();
    private final AtomicInteger loads = new AtomicInteger();
    /** 다음 조회가 할 일. 테스트마다 바꿔 실패·지연을 만든다. 기본은 새 스냅샷이다. */
    private volatile Function<Long, SeatMapSnapshot> nextLoad = CaffeineSelectableSeatCacheTest::snapshot;

    // refresh와 정리 작업을 호출한 스레드에서 바로 실행해, 결과를 기다리지 않고 확인한다
    private final CaffeineSelectableSeatCache cache = cache(Runnable::run);
    private final ExecutorService pool = Executors.newFixedThreadPool(10);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private CaffeineSelectableSeatCache cache(Executor executor) {
        return new CaffeineSelectableSeatCache(
                PROPERTIES,
                id -> {
                    loads.incrementAndGet();
                    return nextLoad.apply(id);
                },
                ticker,
                executor);
    }

    private static SeatMapSnapshot snapshot(long scheduleId, int seatCount) {
        SelectableSeatResult seat = new SelectableSeatResult(1L, 1L, "A", "1", 1, 1L, "R", 10_000, true);
        // 무게는 좌석 수만 보므로 같은 좌석을 반복해 큰 회차를 가볍게 만든다. 비트맵은 무게와 상관없어 비워 둔다
        return new SeatMapSnapshot(
                new SelectableSeatsResult(
                        scheduleId, 1L, LocalDateTime.of(2026, 11, 5, 12, 0), Collections.nCopies(seatCount, seat)),
                "");
    }

    private static SeatMapSnapshot snapshot(long scheduleId) {
        return snapshot(scheduleId, 3);
    }

    @Test
    @DisplayName("refresh 시간 전의 다음 요청은 다시 조회하지 않고 같은 스냅샷을 받는다")
    void sameInstanceBeforeRefresh() {
        SeatMapSnapshot first = cache.get(1L);

        ticker.advance(REFRESH.minusMillis(1));
        SeatMapSnapshot second = cache.get(1L);

        assertThat(second).isSameAs(first);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("refresh 시간이 지난 첫 요청은 기존 스냅샷을 받고 뒤에서 다시 만든다. 그다음 요청은 새 스냅샷을 받는다")
    void staleValueThenRefreshed() {
        // 같은 스레드에서 바로 실행하면 Caffeine이 refresh를 그 자리에서 끝내고 첫 요청에 새 값을 준다. 쌓아 두고 나눠 실행한다
        DeferredTasks refreshTasks = new DeferredTasks();
        CaffeineSelectableSeatCache deferred = cache(refreshTasks::execute);
        SeatMapSnapshot first = deferred.get(1L);

        ticker.advance(PAST_REFRESH);
        SeatMapSnapshot stale = deferred.get(1L);
        assertThat(stale).isSameAs(first);
        assertThat(loads).hasValue(1); // 요청 스레드에서는 다시 만들지 않는다

        refreshTasks.runPending();
        SeatMapSnapshot refreshed = deferred.get(1L);
        assertThat(refreshed).isNotSameAs(first);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("refresh 중 같은 회차 동시 요청 10개는 모두 기존 스냅샷을 바로 받고, 다시 조회는 한 번이다")
    void concurrentRequestsDuringRefreshLoadOnce() throws Exception {
        ExecutorService refreshThread = Executors.newSingleThreadExecutor();
        try {
            CaffeineSelectableSeatCache refreshing = cache(refreshThread);
            SeatMapSnapshot first = refreshing.get(1L);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch reloaded = new CountDownLatch(1);
            nextLoad = id -> {
                await(release); // 모든 요청이 끝날 때까지 refresh를 붙잡아 둔다
                reloaded.countDown();
                return snapshot(id);
            };
            ticker.advance(PAST_REFRESH);

            CountDownLatch start = new CountDownLatch(1);
            List<Future<SeatMapSnapshot>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return refreshing.get(1L);
                }));
            }
            start.countDown();

            for (Future<SeatMapSnapshot> result : results) {
                // refresh가 끝나지 않았어도 기다리지 않고 기존 값을 받는다
                assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(first);
            }
            release.countDown();
            assertThat(reloaded.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(loads).hasValue(2);
        } finally {
            refreshThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("refresh가 실패하면 기존 스냅샷을 계속 주고, 최대 보관 시간이 지나면 만료되어 요청이 직접 조회한다")
    void failedRefreshKeepsOldUntilMaxAge() {
        SeatMapSnapshot first = cache.get(1L);
        nextLoad = id -> {
            throw new IllegalStateException("DB 연결 실패");
        };

        ticker.advance(PAST_REFRESH);
        assertThat(cache.get(1L)).isSameAs(first); // refresh를 시작하고 실패한다
        assertThat(cache.get(1L)).isSameAs(first); // 실패한 refresh는 다음 요청이 다시 시도하고, 또 실패한다
        assertThat(loads).hasValue(3);

        ticker.advance(MAX_AGE.minus(PAST_REFRESH));
        // 만료 뒤에는 기존 값을 주지 않고 요청 스레드에서 조회한다. 그 조회의 예외는 그대로 나간다
        assertThatThrownBy(() -> cache.get(1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("처음 조회가 404 SEAT_NOT_FOUND면 예외가 그대로 나가고 캐시에 남지 않아 다음 요청이 다시 조회한다")
    void notFoundIsNotCached() {
        nextLoad = id -> {
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND);
        };
        assertThatThrownBy(() -> cache.get(1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SEAT_NOT_FOUND));

        nextLoad = CaffeineSelectableSeatCacheTest::snapshot;
        assertThat(cache.get(1L).result().scheduleId()).isEqualTo(1L);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("refresh 전용 스레드가 모두 바쁘고 큐가 차서 거절하면, 요청 스레드에서 조회하지 않고 기존 스냅샷을 준다. 다음 요청이 다시 시도한다")
    void rejectedRefreshKeepsOldValue() throws Exception {
        SeatSnapshotRefreshExecutor refreshExecutor = SeatSnapshotRefreshExecutor.dedicatedThreads();
        CountDownLatch busy = new CountDownLatch(1);
        try {
            CaffeineSelectableSeatCache saturated = cache(refreshExecutor.executor());
            SeatMapSnapshot first = saturated.get(1L);
            // 전용 스레드 2개가 모두 테스트 작업에 묶이고 큐 16칸이 찰 때까지 채운다.
            // 첫 조회 뒤 Caffeine 정리 작업이 스레드를 잠깐 쓰다 끝나면 큐에 자리가 나므로, 두 스레드가 묶인 것을 확인하고 다시 채운다
            CountDownLatch threadsBlocked = new CountDownLatch(SeatSnapshotRefreshExecutor.THREADS);
            Runnable block = () -> {
                threadsBlocked.countDown();
                await(busy);
            };
            fillUntilRejected(refreshExecutor, block);
            assertThat(threadsBlocked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(fillUntilRejected(refreshExecutor, block))
                    .isLessThanOrEqualTo(SeatSnapshotRefreshExecutor.QUEUE_CAPACITY);
            ticker.advance(PAST_REFRESH);

            assertThat(saturated.get(1L)).isSameAs(first);
            assertThat(loads).hasValue(1);

            CountDownLatch reloaded = new CountDownLatch(1);
            nextLoad = id -> {
                reloaded.countDown();
                return snapshot(id);
            };
            busy.countDown();
            // 큐가 비면 다음 요청이 refresh를 다시 시도한다
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (reloaded.getCount() > 0 && System.nanoTime() < deadline) {
                assertThat(saturated.get(1L)).isNotNull();
                reloaded.await(50, TimeUnit.MILLISECONDS);
            }
            assertThat(reloaded.getCount()).isZero();
        } finally {
            busy.countDown();
            refreshExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("회차마다 따로 캐시한다")
    void separatePerSchedule() {
        cache.get(1L);

        SeatMapSnapshot other = cache.get(2L);

        assertThat(other.result().scheduleId()).isEqualTo(2L);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("같은 회차를 10개 요청이 동시에 처음 부르면 조회는 한 번이고 모두 같은 스냅샷을 받는다")
    void concurrentFirstRequestsLoadOnce() throws Exception {
        nextLoad = id -> {
            sleep(200); // 조회가 끝나기 전에 나머지 요청이 모두 도착하게 한다
            return snapshot(id);
        };
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SeatMapSnapshot>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return cache.get(1L);
            }));
        }
        start.countDown();

        List<SeatMapSnapshot> snapshots = new ArrayList<>();
        for (Future<SeatMapSnapshot> result : results) {
            snapshots.add(result.get(10, TimeUnit.SECONDS));
        }
        assertThat(loads).hasValue(1);
        assertThat(snapshots).allMatch(snapshot -> snapshot == snapshots.get(0));
    }

    @Test
    @DisplayName("지표: 처음 조회와 refresh가 load(result=success)에 남고, Caffeine 적중·실패가 cache=seat-snapshot으로 나온다")
    void recordsMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SelectableSeatQueryService queryService = mock(SelectableSeatQueryService.class);
        when(queryService.getSelectableSeats(anyLong()))
                .thenAnswer(invocation -> snapshot(invocation.getArgument(0)).result());
        CaffeineSelectableSeatCache measured = new CaffeineSelectableSeatCache(
                PROPERTIES,
                new SeatMapSnapshotLoader(queryService, new SeatSnapshotMetrics(meters)),
                SeatSnapshotRefreshExecutor.using(Runnable::run),
                ticker,
                meters);

        measured.get(1L); // 실패(처음 조회)
        measured.get(1L); // 적중
        ticker.advance(PAST_REFRESH);
        measured.get(1L); // 적중(기존 값) + refresh

        assertThat(meters.get("reservation.seat.snapshot.load")
                        .tags("result", "success")
                        .timer()
                        .count())
                .isEqualTo(2);
        assertThat(cacheGets(meters, "miss")).isEqualTo(1);
        assertThat(cacheGets(meters, "hit")).isEqualTo(2);
    }

    private static double cacheGets(SimpleMeterRegistry meters, String result) {
        return meters.get("cache.gets")
                .tags("cache", "seat-snapshot", "result", result)
                .functionCounter()
                .count();
    }

    @Test
    @DisplayName("상한은 2단계 목표인 20,000석 회차 하나보다 충분히 크고, 그 회차는 캐시된다")
    void largeScheduleIsCached() {
        assertThat(CaffeineSelectableSeatCache.MAX_CACHED_SEATS).isGreaterThanOrEqualTo(20_000L * 5);
        nextLoad = id -> snapshot(id, 20_000);

        cache.get(1L);
        cache.get(1L);

        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("좌석 수가 상한을 넘는 회차는 넣자마자 버려져 요청마다 다시 조회한다")
    void scheduleHeavierThanLimitIsNotCached() {
        int seats = Math.toIntExact(CaffeineSelectableSeatCache.MAX_CACHED_SEATS + 1);
        nextLoad = id -> snapshot(id, seats);

        cache.get(1L);
        cache.get(1L);

        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("좌석 수 합계가 상한을 넘으면 회차를 버려 합계를 상한 안으로 유지한다")
    void totalSeatsStayWithinLimit() {
        nextLoad = id -> snapshot(id, 60_000); // 상한 150,000석에 3개(180,000석)는 들어가지 않는다
        for (long scheduleId = 1; scheduleId <= 3; scheduleId++) {
            cache.get(scheduleId);
        }

        int before = loads.get();
        nextLoad = id -> snapshot(id, 1);
        for (long scheduleId = 1; scheduleId <= 3; scheduleId++) {
            cache.get(scheduleId);
        }
        // 세 회차가 모두 남아 있다면 다시 조회가 0번이어야 한다. 하나 이상 버려졌어야 한다
        assertThat(loads.get() - before).isPositive();
    }

    /** 거절될 때까지 작업을 넣고, 넣은 개수를 돌려준다. */
    private static int fillUntilRejected(SeatSnapshotRefreshExecutor refreshExecutor, Runnable task) {
        int submitted = 0;
        try {
            while (true) {
                refreshExecutor.executor().execute(task);
                submitted++;
            }
        } catch (RejectedExecutionException full) {
            return submitted;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
