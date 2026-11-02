package com.sparta.ticket.gateway.queue;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Note: 회차가 속한 공연은 바뀌지 않으므로 오래 캐시해 좌석 요청마다 performance-service를 부르지 않는다.
 * 회차가 없다는 결과(404)는 캐시하지 않는다. 아직 공개되지 않은 회차가 나중에 공개될 수 있기 때문이다.
 * AsyncCache는 결과가 null로 끝난 항목을 남기지 않으므로 404(빈 Optional)를 빈 Mono, 즉 null로 끝나는 Future로 바꿔 넘긴다. 조회 실패(503 등)도 남지 않는다.
 * 같은 회차를 동시에 요청하면 조회를 한 번만 하고 결과를 공유한다(CachedUserLookup과 같다).
 */
public class CachedScheduleLookup implements ScheduleLookup {

    private final AsyncCache<Long, Long> cache;
    private final Function<Long, Mono<Optional<Long>>> loader;

    public CachedScheduleLookup(Function<Long, Mono<Optional<Long>>> loader, Duration ttl) {
        this(loader, ttl, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 직접 흘려 만료를 확인할 수 있게 Ticker를 받는다. */
    CachedScheduleLookup(Function<Long, Mono<Optional<Long>>> loader, Duration ttl, Ticker ticker) {
        this.loader = loader;
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(ttl)
                .maximumSize(10_000)
                .buildAsync();
    }

    /** 동시 요청이 같은 Future를 기다리므로 한 요청의 취소가 다른 요청에 번지지 않게 suppressCancel=true로 둔다. */
    @Override
    public Mono<Optional<Long>> performanceIdOf(Long scheduleId) {
        return Mono.fromFuture(
                        () -> cache.get(
                                scheduleId,
                                (key, executor) -> loader.apply(key)
                                        .flatMap(Mono::justOrEmpty)
                                        .toFuture()),
                        true)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }
}
