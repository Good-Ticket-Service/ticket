package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.SeatMapSnapshotLoader;
import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.port.SelectableSeatCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 회차별 좌석 배치도 스냅샷을 서버 메모리에 짧게 두는 캐시(#270).
 *
 * 만든 지 cache-refresh가 지난 스냅샷을 요청하면 기존 값을 바로 돌려주고, refresh 전용 스레드가 뒤에서 다시 만든다(refreshAfterWrite, #311).
 * 같은 회차의 refresh는 Caffeine이 하나로 합친다. refresh가 실패하거나 executor가 거절하면 기존 값을 계속 주고,
 * cache-max-age가 지나면 만료되어 다음 요청이 요청 스레드에서 직접 만든다(expireAfterWrite).
 * 처음 로딩·만료 뒤 로딩은 지금처럼 요청 스레드에서 하고, 같은 회차 동시 요청 중 한 요청만 실행한다. 예외(404 포함)는 저장하지 않는다.
 *
 * 적중·실패·로딩 횟수와 로딩 시간은 Caffeine 통계로 cache=seat-snapshot 지표에 남긴다(#311).
 */
@Component
public class CaffeineSelectableSeatCache implements SelectableSeatCache {

    /**
     * 캐시에 둘 좌석 수 합계 상한. 측정으로 조정하는 값이 아니라 메모리 안전장치라 상수로 둔다.
     * 좌석 1개 약 268B(JOL 측정, #270) × 150,000석 ≈ 38MiB로, 가장 작은 힙(-Xmx384m)의 10%다.
     * refresh 중에는 이전 스냅샷이 캐시에 남은 채 새 스냅샷을 만든다. 새 스냅샷은 넣기 전까지 무게에 들어가지 않지만, 동시에 만드는 수는
     * refresh 전용 스레드 수(SeatSnapshotRefreshExecutor.THREADS = 2)로 묶인다. 그래서 상한 밖 추가분은 최대 회차 2개(20,000석 × 268B × 2 ≈ 11MB)다.
     * 회차 하나의 좌석 수가 상한을 넘으면 넣자마자 버려져 캐시되지 않으므로, 20,000석 회차보다 충분히 커야 한다(테스트로 확인).
     */
    static final long MAX_CACHED_SEATS = 150_000;

    private final LoadingCache<Long, SeatMapSnapshot> cache;

    @Autowired
    public CaffeineSelectableSeatCache(
            SelectableSeatProperties properties,
            SeatMapSnapshotLoader loader,
            SeatSnapshotRefreshExecutor refreshExecutor,
            Ticker ticker,
            MeterRegistry meters) {
        this(properties, loader::load, ticker, refreshExecutor.executor());
        CaffeineCacheMetrics.monitor(meters, cache, "seat-snapshot");
    }

    /** 테스트용. loader와 executor(refresh·정리 작업)를 직접 넣는다. */
    CaffeineSelectableSeatCache(
            SelectableSeatProperties properties,
            Function<Long, SeatMapSnapshot> loader,
            Ticker ticker,
            Executor executor) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .executor(executor)
                .refreshAfterWrite(properties.cacheRefresh())
                .expireAfterWrite(properties.cacheMaxAge())
                .recordStats()
                .maximumWeight(MAX_CACHED_SEATS)
                // 비트맵은 좌석 8개당 1바이트(base64로 약 1.3바이트)라 좌석 1개 약 268B에 비해 무시한다
                .<Long, SeatMapSnapshot>weigher((scheduleId, snapshot) -> snapshot.seatCount())
                .build(loader::apply);
    }

    @Override
    public SeatMapSnapshot get(Long scheduleId) {
        return cache.get(scheduleId);
    }
}
