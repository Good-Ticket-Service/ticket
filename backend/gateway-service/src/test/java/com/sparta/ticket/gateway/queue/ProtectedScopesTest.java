package com.sparta.ticket.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.gateway.infrastructure.client.WaitingRoomClient.ProtectedRoom;
import com.sparta.ticket.gateway.queue.ProtectedScopes.Protection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ProtectedScopesTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
    private static final Instant CLOSE_AT = NOW.plusSeconds(3600);

    private final MutableClock clock = new MutableClock(NOW);

    /** 테스트가 바꿔 끼우는 응답. 실패를 흉내 낼 때는 에러 Mono를 넣는다. */
    private final AtomicReference<Mono<List<ProtectedRoom>>> response = new AtomicReference<>();

    private ProtectedScopes scopes(Supplier<Mono<List<ProtectedRoom>>> loader) {
        return new ProtectedScopes(loader, Duration.ofSeconds(10), clock);
    }

    private ProtectedScopes scopes() {
        return scopes(response::get);
    }

    private static Mono<List<ProtectedRoom>> rooms(ProtectedRoom... rooms) {
        return Mono.just(List.of(rooms));
    }

    @Test
    @DisplayName("한 번도 받지 못했으면 UNKNOWN이다")
    void unknownBeforeFirstLoad() {
        ProtectedScopes scopes = scopes();
        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.UNKNOWN);

        response.set(Mono.error(new IllegalStateException("down")));
        scopes.refresh().block();

        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.UNKNOWN);
    }

    @Test
    @DisplayName("목록의 scope는 closeAt 전까지 PROTECTED, 목록에 없거나 closeAt이 지나면 UNPROTECTED다")
    void protectsUntilCloseAt() {
        ProtectedScopes scopes = scopes();
        response.set(rooms(new ProtectedRoom(List.of("schedule:1", "schedule:2"), CLOSE_AT)));
        scopes.refresh().block();

        assertThat(scopes.protection("schedule:2")).isEqualTo(Protection.PROTECTED);
        assertThat(scopes.protection("schedule:3")).isEqualTo(Protection.UNPROTECTED);

        clock.set(CLOSE_AT.minusMillis(1));
        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.PROTECTED);
        clock.set(CLOSE_AT);
        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.UNPROTECTED);
    }

    @Test
    @DisplayName("받기에 실패하면 마지막 목록을 그대로 쓰고, 다시 받으면 새 목록으로 바뀐다")
    void keepsLastListOnFailure() {
        ProtectedScopes scopes = scopes();
        response.set(rooms(new ProtectedRoom(List.of("schedule:1"), CLOSE_AT)));
        scopes.refresh().block();

        response.set(Mono.error(new IllegalStateException("down")));
        scopes.refresh().block();
        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.PROTECTED);

        response.set(rooms(new ProtectedRoom(List.of("schedule:2"), CLOSE_AT)));
        scopes.refresh().block();
        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.UNPROTECTED);
        assertThat(scopes.protection("schedule:2")).isEqualTo(Protection.PROTECTED);
    }

    @Test
    @DisplayName("빈 목록을 받으면 아무 scope도 보호하지 않는다(UNKNOWN 아님)")
    void emptyListIsLoaded() {
        ProtectedScopes scopes = scopes();
        response.set(rooms());
        scopes.refresh().block();

        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.UNPROTECTED);
    }

    @Test
    @DisplayName("loader가 던져도 refresh는 에러로 끝나지 않는다")
    void loaderThrows() {
        ProtectedScopes scopes = scopes(() -> {
            throw new IllegalStateException("boom");
        });

        scopes.refresh().block();

        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.UNKNOWN);
    }

    @Test
    @DisplayName("start하면 바로 받고 간격마다 다시 받으며, 중간 실패에도 멈추지 않는다. stop하면 멈춘다")
    void pollsUntilStopped() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        ProtectedScopes scopes = new ProtectedScopes(
                () -> calls.incrementAndGet() == 2
                        ? Mono.error(new IllegalStateException("down"))
                        : rooms(new ProtectedRoom(List.of("schedule:1"), CLOSE_AT)),
                Duration.ofMillis(20),
                clock);

        scopes.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (calls.get() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        scopes.stop();

        assertThat(calls.get()).isGreaterThanOrEqualTo(4);
        assertThat(scopes.protection("schedule:1")).isEqualTo(Protection.PROTECTED);
        assertThat(scopes.isRunning()).isFalse();
        int stopped = calls.get();
        Thread.sleep(100);
        assertThat(calls.get()).isEqualTo(stopped);
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
