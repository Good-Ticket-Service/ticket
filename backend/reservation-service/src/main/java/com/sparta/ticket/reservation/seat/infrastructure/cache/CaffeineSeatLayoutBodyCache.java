package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.dto.SeatLayoutBody;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.port.SeatLayoutBodyCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * layout 응답 본문 한 벌(원본과 미리 압축한 gzip)을 서버 메모리에 두는 캐시(#287).
 *
 * 이유: configVersion이 같으면 layout도 같은데, 요청마다 좌석 DTO 목록을 새로 만들고 다시 직렬화했다. 20,000석 layout은 약 3MB라
 * 250/s 측정에서 reservation 할당이 약 130MB/s, GC 정지가 약 1.4%까지 좌석 수에 비례해 늘었다(#287 경계 측정).
 *
 * 무효화: 키에 configVersion이 들어 있어 좌석 준비로 버전이 바뀌면 새 키가 된다. 이전 버전은 읽히지 않다가 expireAfterAccess로 빠진다.
 *
 * 구역(#397): 구역 layout은 키에 구역 이름을 더해 회차 전체 본문과 따로 둔다. 전체 본문에서 구역을 잘라 쓸 수는 없다. 구역 좌석은
 * 회차 좌석 id 순서에서 흩어질 수 있고, gzip은 중간을 잘라 쓸 수 없기 때문이다. 키는 스냅샷에 있는 구역 이름으로만 생긴다(부르는 쪽 확인).
 *
 * 동시 요청: 캐시에 없는 키는 한 요청만 만들고, 같은 키의 다른 요청은 그 결과를 기다린다(Caffeine get). 회차·버전마다 한 번이고
 * DB 없이 직렬화·압축만 하므로 기다림은 짧다. 각자 만들게 하면 예매 오픈 순간 동시 요청 수 × 약 3MB를 한꺼번에 할당한다.
 * 스냅샷 캐시(#311)처럼 1초마다 다시 만드는 값이 아니라서, 기다림이 반복되지 않는다.
 *
 * 압축: 처음 요청은 원본만 만들어 받고 압축은 기다리지 않는다. 압축은 압축 전용 스레드가 키마다 한 번 하고, 끝나면 같은 키의 값을
 * 원본+압축본으로 바꿔 넣는다(무게도 다시 잰다). 그 전에 온 요청은 원본을 받는다. 20,000석 기준 약 50ms이고 버전·서버마다 한 번이다.
 * 압축이 거절되면 다음 요청이 다시 넣는다. 압축이 실패하면 그 키는 원본으로만 주고 다시 시도하지 않는다(메모리 안 압축이라 실제로는 나지 않는다).
 */
@Slf4j
@Component
public class CaffeineSeatLayoutBodyCache implements SeatLayoutBodyCache {

    /**
     * 캐시에 둘 본문 바이트 합계 상한(32MiB). 원본과 gzip을 합해 잰다(SeatLayoutBody.weight). 측정으로 조정하는 값이 아니라 메모리 안전장치라 상수로 둔다.
     * layout을 만들려면 그 회차의 스냅샷이 있어야 하고, 스냅샷 캐시는 좌석 합계 150,000석까지 둔다(CaffeineSelectableSeatCache).
     * 좌석 1개 layout 약 150B(#287 측정) × 150,000석 ≈ 22.5MB에 gzip(원본의 약 6%, 추정)을 더해도
     * 약 24MB라 동시에 쓰이는 회차는 모두 담긴다. 가장 작은 힙(-Xmx384m)의 약 8%다.
     * 구역 본문(#397)도 이 상한을 함께 쓴다. 같은 회차의 전체 본문과 구역 본문이 모두 읽히면 최악 약 2배가 되지만, 큰 공연장은 구역 본문을,
     * 작은 공연장은 전체 본문을 주로 써서 드물다. 넘치면 오래 안 읽힌 본문부터 빠지고 다음 요청이 다시 만든다(버전·구역마다 한 번).
     */
    static final long MAX_CACHED_BYTES = 32L * 1024 * 1024;

    /** 이 시간 동안 아무도 읽지 않은 본문(이전 configVersion, 끝난 회차)을 뺀다. 통합 테스트가 앞 테스트의 본문을 비울 때도 쓴다. */
    public static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    private final Cache<Key, SeatLayoutBody> cache;
    private final Executor compressExecutor;
    /** 압축을 넣었거나 실패한 키. 같은 키를 두 번 압축하지 않는다. 캐시에서 빠진 키가 남아도 다음 버전은 키가 달라 상관없다. */
    private final Set<Key> compressing = ConcurrentHashMap.newKeySet();

    @Autowired
    public CaffeineSeatLayoutBodyCache(
            Ticker ticker, SeatLayoutCompressExecutor compressExecutor, MeterRegistry meters) {
        this(ticker, Runnable::run, compressExecutor.executor());
        CaffeineCacheMetrics.monitor(meters, cache, "seat-layout-body");
        Gauge.builder("reservation.seat.layout.body.bytes", this, CaffeineSeatLayoutBodyCache::cachedBytes)
                .description("캐시에 둔 layout 응답 본문 바이트 합계")
                .baseUnit("bytes")
                .register(meters);
    }

    /** 테스트용. 정리 작업(만료·상한 제거)과 압축을 넣은 executor에서 실행한다. */
    CaffeineSeatLayoutBodyCache(Ticker ticker, Executor executor, Executor compressExecutor) {
        this.compressExecutor = compressExecutor;
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .executor(executor)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS)
                .maximumWeight(MAX_CACHED_BYTES)
                .<Key, SeatLayoutBody>weigher((key, body) -> body.weight())
                .recordStats()
                .build();
    }

    @Override
    public SeatLayoutBody get(SeatMapSnapshot snapshot, String section, Function<SeatMapSnapshot, byte[]> render) {
        Key key = new Key(snapshot.result().scheduleId(), snapshot.configVersion(), section);
        SeatLayoutBody body = cache.get(key, ignored -> SeatLayoutBody.identityOnly(render.apply(snapshot)));
        if (body.needsGzip()) {
            compressLater(key, body);
        }
        return body;
    }

    private void compressLater(Key key, SeatLayoutBody body) {
        if (!compressing.add(key)) {
            return;
        }
        try {
            compressExecutor.execute(() -> compress(key, body));
        } catch (RejectedExecutionException e) {
            // 다음 요청이 다시 넣는다. 그동안은 원본을 준다
            compressing.remove(key);
        }
    }

    private void compress(Key key, SeatLayoutBody body) {
        SeatLayoutBody compressed;
        try {
            compressed = body.compressed();
        } catch (RuntimeException e) {
            // compressing에 남겨 다시 시도하지 않는다. 원본으로 계속 준다
            log.warn(
                    "layout 본문을 압축하지 못했습니다. scheduleId={}, configVersion={}", key.scheduleId(), key.configVersion(), e);
            return;
        }
        // 그사이 빠졌거나 바뀐 값은 건드리지 않는다
        cache.asMap().replace(key, body, compressed);
        compressing.remove(key);
    }

    long cachedBytes() {
        return cache.policy().eviction().orElseThrow().weightedSize().orElse(0L);
    }

    /** section이 null이면 회차 전체 본문이다. */
    private record Key(long scheduleId, long configVersion, String section) {}
}
