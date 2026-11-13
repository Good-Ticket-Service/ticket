package com.sparta.ticket.reservation.seat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.seat.application.dto.SeatLayoutBody;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.support.DeferredTasks;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 * layout 응답 본문 캐시를 Spring 없이 확인한다(#287). 재사용, 키 구분, 동시 요청, 실패, 만료, 바이트 상한, 뒤에서 하는 압축을 본다.
 * 본문 형식은 통합 테스트가 본다.
 */
class CaffeineSeatLayoutBodyCacheTest {

    private final MutableTicker ticker = new MutableTicker();
    // 압축 작업은 쌓아 두었다가 테스트가 실행한다. 압축 전·후를 나눠 본다
    private final DeferredTasks compressTasks = new DeferredTasks();
    // 정리 작업(만료·상한 제거)을 호출한 스레드에서 바로 실행해, 결과를 기다리지 않고 확인한다
    private final CaffeineSeatLayoutBodyCache cache =
            new CaffeineSeatLayoutBodyCache(ticker, Runnable::run, compressTasks::execute);
    private final ExecutorService pool = Executors.newFixedThreadPool(10);
    private final AtomicInteger renders = new AtomicInteger();

    /** 좌석 하나에 원본 150바이트(#287 측정의 좌석당 크기)로 본문을 만든다. 만든 횟수를 센다. */
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
        SeatLayoutBody first = cache.get(snapshot(1L, 2L), render);
        SeatLayoutBody second = cache.get(snapshot(1L, 2L), render);

        assertThat(second).isSameAs(first);
        assertThat(renders).hasValue(1);
    }

    @Test
    @DisplayName("configVersion이나 회차가 다르면 새로 만든다")
    void keyedByScheduleAndConfigVersion() {
        SeatLayoutBody v2 = cache.get(snapshot(1L, 2L), render);
        SeatLayoutBody v3 = cache.get(snapshot(1L, 3L), render);
        SeatLayoutBody otherSchedule = cache.get(snapshot(2L, 2L), render);

        assertThat(v3).isNotSameAs(v2);
        assertThat(otherSchedule).isNotSameAs(v2);
        assertThat(renders).hasValue(3);
    }

    @Test
    @DisplayName("같은 회차·configVersion이어도 전체 본문과 구역 본문, 구역끼리는 따로 만들고, 같은 구역은 다시 쓴다(#397)")
    void keyedBySection() {
        SeatLayoutBody whole = cache.get(snapshot(1L, 2L), render);
        SeatLayoutBody a = cache.get(snapshot(1L, 2L), "A", render);
        SeatLayoutBody b = cache.get(snapshot(1L, 2L), "B", render);

        assertThat(a).isNotSameAs(whole);
        assertThat(b).isNotSameAs(a);
        assertThat(cache.get(snapshot(1L, 2L), "A", render)).isSameAs(a);
        assertThat(cache.get(snapshot(1L, 2L), render)).isSameAs(whole);
        assertThat(renders).hasValue(3);
    }

    @Test
    @DisplayName("구역 본문도 키마다 따로 뒤에서 압축한다(#397)")
    void compressesSectionBodySeparately() {
        AtomicInteger submitted = new AtomicInteger();
        CaffeineSeatLayoutBodyCache counting = new CaffeineSeatLayoutBodyCache(ticker, Runnable::run, task -> {
            submitted.incrementAndGet();
            compressTasks.execute(task);
        });

        counting.get(snapshot(1L, 2L, 100), render);
        counting.get(snapshot(1L, 2L, 100), "A", render);
        compressTasks.runPending();

        assertThat(submitted).hasValue(2);
        assertThat(counting.get(snapshot(1L, 2L, 100), "A", render).gzip()).isNotNull();
        assertThat(counting.get(snapshot(1L, 2L, 100), render).gzip()).isNotNull();
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

        List<Future<SeatLayoutBody>> results = new ArrayList<>();
        results.add(pool.submit(() -> cache.get(snapshot(1L, 2L), slowRender)));
        // 첫 요청이 본문을 만드는 중에 나머지 9건을 보낸다
        assertThat(rendering.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 9; i++) {
            results.add(pool.submit(() -> cache.get(snapshot(1L, 2L), slowRender)));
        }
        Thread.sleep(100);
        release.countDown();

        SeatLayoutBody first = results.get(0).get(5, TimeUnit.SECONDS);
        for (Future<SeatLayoutBody> result : results) {
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
        SeatLayoutBody first = cache.get(snapshot(1L, 2L), render);

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
    @DisplayName("25,000석 회차 하나(원본 약 3.7MB + 압축본)는 상한보다 충분히 작아 캐시에 남는다")
    void largeScheduleStaysCached() {
        SeatLayoutBody first = cache.get(snapshot(1L, 1L, 25_000), render);

        assertThat(cache.get(snapshot(1L, 1L, 25_000), render)).isSameAs(first);
        assertThat(cache.cachedBytes()).isEqualTo(first.identity().length);
        // 압축이 끝나면 무게는 원본과 압축본의 합이다
        compressTasks.runPending();
        SeatLayoutBody compressed = cache.get(snapshot(1L, 1L, 25_000), render);
        assertThat(cache.cachedBytes()).isEqualTo(compressed.identity().length + compressed.gzip().length);
    }

    @Test
    @DisplayName("처음 요청은 압축을 기다리지 않고 원본만 받고, 압축이 끝난 뒤 요청은 같은 원본에 압축본이 더해진 본문을 받는다")
    void compressesInBackground() {
        SeatLayoutBody first = cache.get(snapshot(1L, 2L, 100), render);
        assertThat(first.gzip()).isNull();
        // 압축 전 다음 요청도 원본이다. 압축 작업은 키마다 하나만 넣는다
        assertThat(cache.get(snapshot(1L, 2L, 100), render)).isSameAs(first);

        compressTasks.runPending();

        SeatLayoutBody compressed = cache.get(snapshot(1L, 2L, 100), render);
        assertThat(compressed.identity()).isSameAs(first.identity());
        assertThat(compressed.gzip()).isNotNull();
        assertThat(renders).hasValue(1);
    }

    @Test
    @DisplayName("압축은 키마다 한 번이다: 압축 전 요청이 여러 번 와도 작업은 하나다")
    void compressOncePerKey() {
        AtomicInteger submitted = new AtomicInteger();
        CaffeineSeatLayoutBodyCache counting = new CaffeineSeatLayoutBodyCache(ticker, Runnable::run, task -> {
            submitted.incrementAndGet();
            compressTasks.execute(task);
        });

        for (int i = 0; i < 5; i++) {
            counting.get(snapshot(1L, 2L, 100), render);
        }
        compressTasks.runPending();
        counting.get(snapshot(1L, 2L, 100), render);

        assertThat(submitted).hasValue(1);
    }

    @Test
    @DisplayName("압축 작업이 거절되면 그동안 원본을 주고, 다음 요청이 다시 넣는다")
    void rejectedCompressRetriesOnNextRequest() {
        AtomicInteger attempts = new AtomicInteger();
        CaffeineSeatLayoutBodyCache rejecting = new CaffeineSeatLayoutBodyCache(ticker, Runnable::run, task -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RejectedExecutionException("가득 참");
            }
            task.run();
        });

        assertThat(rejecting.get(snapshot(1L, 2L, 100), render).gzip()).isNull();
        // 두 번째 요청이 압축을 다시 넣어 바로 실행된다. 이 요청은 넣기 전의 원본을 받는다
        assertThat(rejecting.get(snapshot(1L, 2L, 100), render).gzip()).isNull();
        assertThat(rejecting.get(snapshot(1L, 2L, 100), render).gzip()).isNotNull();
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("2KB보다 작은 본문은 압축 작업을 넣지 않는다")
    void smallBodyNotCompressed() {
        AtomicInteger submitted = new AtomicInteger();
        CaffeineSeatLayoutBodyCache counting =
                new CaffeineSeatLayoutBodyCache(ticker, Runnable::run, task -> submitted.incrementAndGet());

        // 좌석 3개 × 150바이트 = 450바이트
        assertThat(counting.get(snapshot(1L, 2L), render).gzip()).isNull();
        assertThat(submitted).hasValue(0);
    }
}
