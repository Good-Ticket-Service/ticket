package com.sparta.ticket.reservation.seat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** layout 응답 본문 캐시를 Spring 없이 확인한다(#287). 재사용, 키 구분, 동시 요청, 실패, 만료, 바이트 상한을 본다. 본문 형식은 통합 테스트가 본다. */
class CaffeineSeatLayoutBodyCacheTest {

    private final MutableTicker ticker = new MutableTicker();
    // 정리 작업(만료·상한 제거)을 호출한 스레드에서 바로 실행해, 결과를 기다리지 않고 확인한다
    private final CaffeineSeatLayoutBodyCache cache = new CaffeineSeatLayoutBodyCache(ticker, Runnable::run);
    private final ExecutorService pool = Executors.newFixedThreadPool(10);
    private final AtomicInteger renders = new AtomicInteger();

    /** 좌석 하나에 150바이트로 본문을 만든다(#287 측정의 좌석당 크기). 만든 횟수를 센다. */
    private final Function<SeatMapSnapshot, byte[]> render = snapshot -> {
        renders.incrementAndGet();
        return new byte[snapshot.seatCount() * 150];
    };

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static SeatMapSnapshot snapshot(long scheduleId, long configVersion, int seatCount) {
        SelectableSeatResult seat = new SelectableSeatResult(1L, 10L, "A", "1", 1, 100L, "R", 10_000, true);
        return new SeatMapSnapshot(
                new SelectableSeatsResult(
                        scheduleId,
                        configVersion,
                        LocalDateTime.of(2026, 11, 11, 12, 0),
                        Collections.nCopies(seatCount, seat)),
                "");
    }

    private static SeatMapSnapshot snapshot(long scheduleId, long configVersion) {
        return snapshot(scheduleId, configVersion, 3);
    }

    @Test
    @DisplayName("같은 회차·configVersion이면 다른 스냅샷(1초마다 새로 만든 것)이어도 처음 만든 본문을 다시 쓴다")
    void reusedAcrossSnapshots() {
        byte[] first = cache.get(snapshot(1L, 2L), render);
        byte[] second = cache.get(snapshot(1L, 2L), render);

        assertThat(second).isSameAs(first);
        assertThat(renders).hasValue(1);
    }

    @Test
    @DisplayName("configVersion이나 회차가 다르면 새로 만든다")
    void keyedByScheduleAndConfigVersion() {
        byte[] v2 = cache.get(snapshot(1L, 2L), render);
        byte[] v3 = cache.get(snapshot(1L, 3L), render);
        byte[] otherSchedule = cache.get(snapshot(2L, 2L), render);

        assertThat(v3).isNotSameAs(v2);
        assertThat(otherSchedule).isNotSameAs(v2);
        assertThat(renders).hasValue(3);
    }

    @Test
    @DisplayName("캐시에 없을 때 같은 키 동시 요청 10건은 본문을 한 번만 만들고 모두 같은 본문을 받는다")
    void concurrentMissRendersOnce() throws Exception {
        CountDownLatch rendering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Function<SeatMapSnapshot, byte[]> slowRender = snapshot -> {
            rendering.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return render.apply(snapshot);
        };

        List<Future<byte[]>> results = new ArrayList<>();
        results.add(pool.submit(() -> cache.get(snapshot(1L, 2L), slowRender)));
        // 첫 요청이 본문을 만드는 중에 나머지 9건을 보낸다
        assertThat(rendering.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 9; i++) {
            results.add(pool.submit(() -> cache.get(snapshot(1L, 2L), slowRender)));
        }
        Thread.sleep(100);
        release.countDown();

        byte[] first = results.get(0).get(5, TimeUnit.SECONDS);
        for (Future<byte[]> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(first);
        }
        assertThat(renders).hasValue(1);
    }

    @Test
    @DisplayName("본문을 만들다 실패하면 저장하지 않고 예외를 그대로 던지며, 다음 요청이 다시 만든다")
    void failedRenderIsNotCached() {
        IllegalStateException failure = new IllegalStateException("직렬화 실패");

        assertThatThrownBy(() -> cache.get(snapshot(1L, 2L), snapshot -> {
                    throw failure;
                }))
                .isSameAs(failure);

        cache.get(snapshot(1L, 2L), render);
        assertThat(renders).hasValue(1);
    }

    @Test
    @DisplayName("읽히는 동안은 남고, 마지막으로 읽힌 뒤 만료 시간이 지나면 다시 만든다")
    void expiresAfterAccess() {
        byte[] first = cache.get(snapshot(1L, 2L), render);

        ticker.advance(CaffeineSeatLayoutBodyCache.EXPIRE_AFTER_ACCESS.minusMinutes(1));
        assertThat(cache.get(snapshot(1L, 2L), render)).isSameAs(first);
        ticker.advance(CaffeineSeatLayoutBodyCache.EXPIRE_AFTER_ACCESS.minusMinutes(1));
        assertThat(cache.get(snapshot(1L, 2L), render)).isSameAs(first);

        ticker.advance(CaffeineSeatLayoutBodyCache.EXPIRE_AFTER_ACCESS);
        assertThat(cache.get(snapshot(1L, 2L), render)).isNotSameAs(first);
        assertThat(renders).hasValue(2);
    }

    @Test
    @DisplayName("바이트 합계가 상한을 넘으면 일부를 빼고, 합계는 상한 안에 머문다")
    void boundedByBytes() {
        // 20,000석 회차는 약 3MB다. 30회차면 약 90MB로 상한(32MiB)을 넘는다
        for (long scheduleId = 1; scheduleId <= 30; scheduleId++) {
            cache.get(snapshot(scheduleId, 1L, 20_000), render);
        }

        assertThat(cache.cachedBytes()).isPositive().isLessThanOrEqualTo(CaffeineSeatLayoutBodyCache.MAX_CACHED_BYTES);
    }

    @Test
    @DisplayName("25,000석 회차 하나(약 3.7MB)는 상한보다 충분히 작아 캐시에 남는다")
    void largeScheduleStaysCached() {
        byte[] first = cache.get(snapshot(1L, 1L, 25_000), render);

        assertThat(cache.get(snapshot(1L, 1L, 25_000), render)).isSameAs(first);
        assertThat(cache.cachedBytes()).isEqualTo(first.length);
    }
}
