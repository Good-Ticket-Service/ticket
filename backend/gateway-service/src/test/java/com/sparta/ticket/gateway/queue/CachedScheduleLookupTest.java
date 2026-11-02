package com.sparta.ticket.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CachedScheduleLookupTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();

    private CachedScheduleLookup lookup(Mono<Optional<Long>> result) {
        return new CachedScheduleLookup(
                id -> {
                    calls.incrementAndGet();
                    return result;
                },
                Duration.ofHours(1),
                nanos::get);
    }

    @Test
    @DisplayName("한 시간 안에서는 같은 회차를 다시 조회하지 않고 캐시된 공연을 쓴다")
    void cachesWithinTtl() {
        CachedScheduleLookup lookup = lookup(Mono.just(Optional.of(3L)));

        lookup.performanceIdOf(42L).block();
        nanos.addAndGet(Duration.ofMinutes(59).toNanos());

        assertThat(lookup.performanceIdOf(42L).block()).contains(3L);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("한 시간이 지나면 다시 조회한다")
    void reloadsAfterTtl() {
        CachedScheduleLookup lookup = lookup(Mono.just(Optional.of(3L)));

        lookup.performanceIdOf(42L).block();
        nanos.addAndGet(Duration.ofMinutes(61).toNanos());
        lookup.performanceIdOf(42L).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("회차가 없다는 결과(404)는 캐시하지 않는다. 나중에 공개된 회차를 다음 요청에서 찾는다")
    void doesNotCacheNotFound() {
        CachedScheduleLookup lookup = new CachedScheduleLookup(
                id -> Mono.just(calls.incrementAndGet() == 1 ? Optional.<Long>empty() : Optional.of(3L)),
                Duration.ofHours(1),
                nanos::get);

        assertThat(lookup.performanceIdOf(42L).block()).isEmpty();
        assertThat(lookup.performanceIdOf(42L).block()).contains(3L);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("조회 실패는 캐시하지 않고 다음 요청에서 다시 조회한다")
    void doesNotCacheFailure() {
        CachedScheduleLookup lookup = new CachedScheduleLookup(
                id -> calls.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException("performance-service 503"))
                        : Mono.just(Optional.of(3L)),
                Duration.ofHours(1),
                nanos::get);

        assertThatThrownBy(() -> lookup.performanceIdOf(42L).block()).hasMessageContaining("503");
        assertThat(lookup.performanceIdOf(42L).block()).contains(3L);
        assertThat(calls).hasValue(2);
    }
}
