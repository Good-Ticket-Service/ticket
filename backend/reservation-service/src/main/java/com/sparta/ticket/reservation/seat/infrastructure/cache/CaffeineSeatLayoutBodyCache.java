package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.port.SeatLayoutBodyCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * layout 응답 본문을 서버 메모리에 두는 캐시(#287).
 *
 * 이유: configVersion이 같으면 layout도 같은데, 요청마다 좌석 DTO 목록을 새로 만들고 다시 직렬화했다. 20,000석 layout은 약 3MB라
 * 250/s 측정에서 reservation 할당이 약 130MB/s, GC 정지가 약 1.4%까지 좌석 수에 비례해 늘었다(#287 경계 측정).
 *
 * 무효화: 키에 configVersion이 들어 있어 좌석 준비로 버전이 바뀌면 새 키가 된다. 이전 버전은 읽히지 않다가 expireAfterAccess로 빠진다.
 *
 * 동시 요청: 캐시에 없는 키는 한 요청만 만들고, 같은 키의 다른 요청은 그 결과를 기다린다(Caffeine get). 회차·버전마다 한 번이고
 * DB 없이 직렬화만 하므로 기다림은 짧다. 각자 만들게 하면 예매 오픈 순간 동시 요청 수 × 약 3MB를 한꺼번에 할당한다.
 * 스냅샷 캐시(#311)처럼 1초마다 다시 만드는 값이 아니라서, 기다림이 반복되지 않는다.
 */
@Component
public class CaffeineSeatLayoutBodyCache implements SeatLayoutBodyCache {

    /**
     * 캐시에 둘 본문 바이트 합계 상한(32MiB). 측정으로 조정하는 값이 아니라 메모리 안전장치라 상수로 둔다.
     * layout을 만들려면 그 회차의 스냅샷이 있어야 하고, 스냅샷 캐시는 좌석 합계 150,000석까지 둔다(CaffeineSelectableSeatCache).
     * 좌석 1개 layout 약 150B(#287 측정) × 150,000석 ≈ 22.5MB라 동시에 쓰이는 회차는 모두 담긴다. 가장 작은 힙(-Xmx384m)의 약 8%다.
     */
    static final long MAX_CACHED_BYTES = 32L * 1024 * 1024;

    /** 이 시간 동안 아무도 읽지 않은 본문(이전 configVersion, 끝난 회차)을 뺀다. 통합 테스트가 앞 테스트의 본문을 비울 때도 쓴다. */
    public static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    private final Cache<Key, byte[]> cache;

    @Autowired
    public CaffeineSeatLayoutBodyCache(Ticker ticker, MeterRegistry meters) {
        this(ticker, Runnable::run);
        CaffeineCacheMetrics.monitor(meters, cache, "seat-layout-body");
        Gauge.builder("reservation.seat.layout.body.bytes", this, CaffeineSeatLayoutBodyCache::cachedBytes)
                .description("캐시에 둔 layout 응답 본문 바이트 합계")
                .baseUnit("bytes")
                .register(meters);
    }

    /** 테스트용. 정리 작업(만료·상한 제거)을 넣은 executor에서 실행한다. */
    CaffeineSeatLayoutBodyCache(Ticker ticker, Executor executor) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .executor(executor)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS)
                .maximumWeight(MAX_CACHED_BYTES)
                .<Key, byte[]>weigher((key, body) -> body.length)
                .recordStats()
                .build();
    }

    @Override
    public byte[] get(SeatMapSnapshot snapshot, Function<SeatMapSnapshot, byte[]> render) {
        Key key = new Key(snapshot.result().scheduleId(), snapshot.configVersion());
        return cache.get(key, ignored -> render.apply(snapshot));
    }

    long cachedBytes() {
        return cache.policy().eviction().orElseThrow().weightedSize().orElse(0L);
    }

    private record Key(long scheduleId, long configVersion) {}
}
