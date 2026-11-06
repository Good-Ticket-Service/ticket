package com.sparta.ticket.reservation.seat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 좌석 배치도 캐시를 Spring 없이 확인한다. TTL, 동시 요청의 조회 횟수, 실패한 조회, 좌석 수 기준 상한을 본다. */
class CaffeineSelectableSeatCacheTest {

    private static final Duration TTL = Duration.ofSeconds(1);

    private final MutableTicker ticker = new MutableTicker();
    // 정리 작업을 호출한 스레드에서 바로 실행해, 상한 초과 제거를 기다리지 않고 확인한다
    private final CaffeineSelectableSeatCache cache =
            new CaffeineSelectableSeatCache(new SelectableSeatProperties(TTL), ticker, Runnable::run);
    private final ExecutorService pool = Executors.newFixedThreadPool(10);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
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
    @DisplayName("TTL 안의 다음 요청은 다시 조회하지 않고 같은 스냅샷을 받는다")
    void cachesWithinTtl() {
        AtomicInteger loads = new AtomicInteger();

        SeatMapSnapshot first = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return snapshot(1L);
        });
        SeatMapSnapshot second = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return snapshot(1L);
        });

        assertThat(second).isSameAs(first);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("TTL이 지나면 다시 조회한다")
    void reloadsAfterTtl() {
        AtomicInteger loads = new AtomicInteger();
        SeatMapSnapshot first = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return snapshot(1L);
        });

        ticker.advance(TTL.minusMillis(1));
        assertThat(cache.getOrLoad(1L, () -> snapshot(1L))).isSameAs(first);

        ticker.advance(Duration.ofMillis(1));
        SeatMapSnapshot reloaded = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return snapshot(1L);
        });
        assertThat(reloaded).isNotSameAs(first);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("같은 회차를 10개 요청이 동시에 처음 부르면 조회는 한 번이고 모두 같은 스냅샷을 받는다")
    void concurrentRequestsLoadOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SeatMapSnapshot>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return cache.getOrLoad(1L, () -> {
                    loads.incrementAndGet();
                    sleep(200); // 조회가 끝나기 전에 나머지 요청이 모두 도착하게 한다
                    return snapshot(1L);
                });
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
    @DisplayName("조회가 실패하면 예외가 그대로 나가고 캐시에 남지 않아 다음 요청이 다시 조회한다")
    void failedLoadIsNotCached() {
        AtomicInteger loads = new AtomicInteger();
        assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
                    loads.incrementAndGet();
                    throw new IllegalStateException("좌석 없음");
                }))
                .isInstanceOf(IllegalStateException.class);

        cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return snapshot(1L);
        });
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("회차마다 따로 캐시한다")
    void separatePerSchedule() {
        cache.getOrLoad(1L, () -> snapshot(1L));

        SeatMapSnapshot other = cache.getOrLoad(2L, () -> snapshot(2L));

        assertThat(other.result().scheduleId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("상한은 2단계 목표인 20,000석 회차 하나보다 충분히 크고, 그 회차는 캐시된다")
    void largeScheduleIsCached() {
        assertThat(CaffeineSelectableSeatCache.MAX_CACHED_SEATS).isGreaterThanOrEqualTo(20_000L * 5);
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            cache.getOrLoad(1L, () -> {
                loads.incrementAndGet();
                return snapshot(1L, 20_000);
            });
        }

        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("좌석 수가 상한을 넘는 회차는 넣자마자 버려져 요청마다 다시 조회한다")
    void scheduleHeavierThanLimitIsNotCached() {
        int seats = Math.toIntExact(CaffeineSelectableSeatCache.MAX_CACHED_SEATS + 1);
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            cache.getOrLoad(1L, () -> {
                loads.incrementAndGet();
                return snapshot(1L, seats);
            });
        }

        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("좌석 수 합계가 상한을 넘으면 회차를 버려 합계를 상한 안으로 유지한다")
    void totalSeatsStayWithinLimit() {
        int seats = 60_000; // 상한 150,000석에 3개(180,000석)는 들어가지 않는다
        for (long scheduleId = 1; scheduleId <= 3; scheduleId++) {
            long id = scheduleId;
            cache.getOrLoad(id, () -> snapshot(id, seats));
        }

        AtomicInteger reloads = new AtomicInteger();
        for (long scheduleId = 1; scheduleId <= 3; scheduleId++) {
            long id = scheduleId;
            cache.getOrLoad(id, () -> {
                reloads.incrementAndGet();
                return snapshot(id, 1);
            });
        }
        // 세 회차가 모두 남아 있다면 다시 조회가 0번이어야 한다. 하나 이상 버려졌어야 한다
        assertThat(reloads.get()).isPositive();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
