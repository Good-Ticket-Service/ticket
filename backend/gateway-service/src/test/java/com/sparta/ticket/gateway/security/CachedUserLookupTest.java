package com.sparta.ticket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CachedUserLookupTest {

    private final Long userId = 1L;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();

    private CachedUserLookup lookup(String... roles) {
        return new CachedUserLookup(
                id -> {
                    String role = roles[Math.min(calls.getAndIncrement(), roles.length - 1)];
                    return Mono.just(Optional.of(new UserSnapshot(id, "hong@ticket.com", role)));
                },
                Duration.ofSeconds(60),
                nanos::get);
    }

    @Test
    @DisplayName("60초 안에서는 user-service를 다시 조회하지 않고 캐시된 값을 쓴다")
    void cachesWithinTtl() {
        CachedUserLookup lookup = lookup("USER", "MASTER");

        lookup.find(userId).block();
        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        Optional<UserSnapshot> cached = lookup.find(userId).block();

        assertThat(cached).map(UserSnapshot::role).contains("USER");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("60초가 지나면 user-service를 다시 조회해 바뀐 role을 반영한다")
    void reloadsAfterTtl() {
        CachedUserLookup lookup = lookup("USER", "MASTER");

        lookup.find(userId).block();
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        Optional<UserSnapshot> reloaded = lookup.find(userId).block();

        assertThat(reloaded).map(UserSnapshot::role).contains("MASTER");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("조회 실패는 캐시하지 않고 다음 요청에서 다시 조회한다")
    void doesNotCacheFailure() {
        CachedUserLookup lookup = new CachedUserLookup(
                id -> calls.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException("down"))
                        : Mono.just(Optional.empty()),
                Duration.ofSeconds(60),
                nanos::get);

        assertThatThrownBy(() -> lookup.find(userId).block()).hasMessageContaining("down");
        assertThat(lookup.find(userId).block()).isEmpty();
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("같은 사용자의 동시 요청 중 하나가 취소돼도 다른 요청은 결과를 받는다")
    void cancelDoesNotBreakOthers() {
        CachedUserLookup lookup = new CachedUserLookup(
                id -> {
                    calls.incrementAndGet();
                    return Mono.delay(java.time.Duration.ofMillis(200))
                            .map(ignored -> Optional.of(new UserSnapshot(id, "hong@ticket.com", "USER")));
                },
                Duration.ofSeconds(60),
                nanos::get);

        reactor.core.Disposable first = lookup.find(userId).subscribe();
        Mono<Optional<UserSnapshot>> second = lookup.find(userId);
        first.dispose();

        assertThat(second.block(Duration.ofSeconds(2))).map(UserSnapshot::role).contains("USER");
        assertThat(calls).hasValue(1);
    }
}
