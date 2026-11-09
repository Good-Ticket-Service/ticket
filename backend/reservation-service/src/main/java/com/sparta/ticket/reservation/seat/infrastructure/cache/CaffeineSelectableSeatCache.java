package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.port.SelectableSeatCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 회차별 좌석 배치도 스냅샷을 서버 메모리에 짧게 두는 캐시. 잔여석 캐시(CaffeineRemainingSeatCache)와 같은 구조다.
 *
 * 적중·실패·로딩 횟수와 로딩 시간은 Caffeine 통계로 cache=seat-snapshot 지표에 남긴다(#311).
 *
 * Note: Cache.get(key, loader)는 같은 회차 동시 요청 중 한 요청만 loader를 실행한다. loader 예외는 저장하지 않는다.
 */
@Component
public class CaffeineSelectableSeatCache implements SelectableSeatCache {

    /**
     * 캐시에 둘 좌석 수 합계 상한. 측정으로 조정하는 값이 아니라 메모리 안전장치라 상수로 둔다.
     * 좌석 1개 약 268B(JOL 측정, #270) × 150,000석 ≈ 38MiB로, 가장 작은 힙(-Xmx384m)의 10%다.
     * 이전·새 스냅샷이 갱신 순간 잠깐 함께 있을 수 있어 여유를 둔다.
     * 회차 하나의 좌석 수가 상한을 넘으면 넣자마자 버려져 캐시되지 않으므로, 20,000석 회차보다 충분히 커야 한다(테스트로 확인).
     */
    static final long MAX_CACHED_SEATS = 150_000;

    private final Cache<Long, SeatMapSnapshot> cache;

    @Autowired
    public CaffeineSelectableSeatCache(SelectableSeatProperties properties, Ticker ticker, MeterRegistry meters) {
        this(properties, ticker, ForkJoinPool.commonPool());
        CaffeineCacheMetrics.monitor(meters, cache, "seat-snapshot");
    }

    /** 테스트용. 정리 작업(만료·상한 초과 제거)을 호출한 스레드에서 바로 실행하게 해 결과를 기다리지 않고 확인한다. */
    CaffeineSelectableSeatCache(SelectableSeatProperties properties, Ticker ticker, Executor executor) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .executor(executor)
                .expireAfterWrite(properties.cacheTtl())
                .recordStats()
                .maximumWeight(MAX_CACHED_SEATS)
                // 비트맵은 좌석 8개당 1바이트(base64로 약 1.3바이트)라 좌석 1개 약 268B에 비해 무시한다
                .<Long, SeatMapSnapshot>weigher((scheduleId, snapshot) -> snapshot.seatCount())
                .build();
    }

    @Override
    public SeatMapSnapshot getOrLoad(Long scheduleId, Supplier<SeatMapSnapshot> loader) {
        return cache.get(scheduleId, id -> loader.get());
    }
}
