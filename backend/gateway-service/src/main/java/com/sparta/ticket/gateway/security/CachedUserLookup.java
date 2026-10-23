package com.sparta.ticket.gateway.security;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Note: 요청마다 user-service를 부르지 않도록 사용자 조회 결과를 60초 동안 로컬 캐시한다. 권한 변경과 탈퇴는
 * 최대 60초 늦게 반영되는 대신 user-service 호출이 사용자당 1분에 한 번으로 줄어든다. AsyncCache는 같은 키를
 * 동시에 요청해도 조회를 한 번만 하고 결과를 공유한다. 조회 실패(503 등)는 캐시에 남지 않아 다음 요청에서 다시 시도한다.
 */
public class CachedUserLookup implements UserLookup {

    private final AsyncCache<UUID, Optional<UserSnapshot>> cache;
    private final Function<UUID, Mono<Optional<UserSnapshot>>> loader;

    public CachedUserLookup(Function<UUID, Mono<Optional<UserSnapshot>>> loader, Duration ttl) {
        this(loader, ttl, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 직접 흘려 만료를 확인할 수 있게 Ticker를 받는다. */
    CachedUserLookup(Function<UUID, Mono<Optional<UserSnapshot>>> loader, Duration ttl, Ticker ticker) {
        this.loader = loader;
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(ttl)
                .maximumSize(10_000)
                .buildAsync();
    }

    /**
     * Note: 같은 사용자의 동시 요청은 하나의 Future를 공유한다. Mono.fromFuture는 기본적으로 구독이 취소되면 Future도 취소하므로,
     * 클라이언트 하나가 연결을 끊으면 같은 Future를 기다리던 다른 요청까지 실패한다. suppressCancel=true로 취소를 전달하지 않는다.
     */
    @Override
    public Mono<Optional<UserSnapshot>> find(UUID userId) {
        return Mono.fromFuture(
                () -> cache.get(userId, (key, executor) -> loader.apply(key).toFuture()), true);
    }
}
