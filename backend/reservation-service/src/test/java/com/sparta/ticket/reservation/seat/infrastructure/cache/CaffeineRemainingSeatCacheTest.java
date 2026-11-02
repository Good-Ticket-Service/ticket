package com.sparta.ticket.reservation.seat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.seat.application.port.RemainingSeatCache.Lookup;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatProperties;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeats;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

/** 잔여석 캐시를 Spring 없이 확인한다. 동시 요청의 집계 횟수, 실패한 집계, TTL 만료를 본다. */
class CaffeineRemainingSeatCacheTest {

    private static final Duration TTL = Duration.ofSeconds(3);

    private final MutableTicker ticker = new MutableTicker();
    private final CaffeineRemainingSeatCache cache =
            new CaffeineRemainingSeatCache(new RemainingSeatProperties(TTL), ticker);
    private final ExecutorService pool = Executors.newFixedThreadPool(10);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static RemainingSeats seats(long scheduleId) {
        return new RemainingSeats(scheduleId, List.of(), LocalDateTime.of(2026, 11, 2, 12, 0));
    }

    @Test
    @DisplayName("처음 요청은 집계하고 cached=false, TTL 안의 다음 요청은 같은 값을 cached=true로 받는다")
    void cachesWithinTtl() {
        AtomicInteger loads = new AtomicInteger();

        Lookup first = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return seats(1L);
        });
        Lookup second = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return seats(1L);
        });

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.seats()).isSameAs(first.seats());
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("TTL이 지나면 다시 집계한다")
    void reloadsAfterTtl() {
        AtomicInteger loads = new AtomicInteger();
        cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return seats(1L);
        });

        ticker.advance(TTL.minusMillis(1));
        assertThat(cache.getOrLoad(1L, () -> seats(1L)).cached()).isTrue();

        ticker.advance(Duration.ofMillis(1));
        Lookup reloaded = cache.getOrLoad(1L, () -> {
            loads.incrementAndGet();
            return seats(1L);
        });
        assertThat(reloaded.cached()).isFalse();
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("같은 회차를 10개 요청이 동시에 처음 부르면 집계는 한 번이고 모두 같은 결과를 받는다")
    void concurrentRequestsLoadOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Lookup>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return cache.getOrLoad(1L, () -> {
                    loads.incrementAndGet();
                    sleep(200); // 집계가 끝나기 전에 나머지 요청이 모두 도착하게 한다
                    return seats(1L);
                });
            }));
        }
        start.countDown();

        List<Lookup> lookups = new ArrayList<>();
        for (Future<Lookup> result : results) {
            lookups.add(result.get(10, TimeUnit.SECONDS));
        }
        assertThat(loads).hasValue(1);
        assertThat(lookups)
                .extracting(Lookup::seats)
                .allMatch(seats -> seats == lookups.get(0).seats());
        assertThat(lookups).filteredOn(lookup -> !lookup.cached()).hasSize(1);
    }

    @Test
    @DisplayName("집계가 실패하면 예외가 그대로 나가고 캐시에 남지 않아 다음 요청이 다시 집계한다")
    void failedLoadIsNotCached() {
        assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
                    throw new IllegalStateException("DB 오류");
                }))
                .isInstanceOf(IllegalStateException.class);

        Lookup next = cache.getOrLoad(1L, () -> seats(1L));
        assertThat(next.cached()).isFalse();
    }

    @Test
    @DisplayName("회차마다 따로 캐시한다")
    void separatePerSchedule() {
        cache.getOrLoad(1L, () -> seats(1L));

        Lookup other = cache.getOrLoad(2L, () -> seats(2L));

        assertThat(other.cached()).isFalse();
        assertThat(other.seats().scheduleId()).isEqualTo(2L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
