package com.sparta.ticket.reservation.seat.application.hold;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConvertConcurrencyLimitTest {

    private ConvertConcurrencyLimit limit(int max) {
        return new ConvertConcurrencyLimit(
                new ConvertLimitProperties(max, Duration.ofSeconds(1), Duration.ofSeconds(10)),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("상한이 0이면 몇 개든 받는다")
    void unlimitedWhenZero() {
        ConvertConcurrencyLimit limit = limit(0);

        for (int i = 0; i < 1_000; i++) {
            assertThat(limit.tryEnter()).isTrue();
        }
    }

    @Test
    @DisplayName("상한까지만 받고, 하나가 끝나면 다시 받는다")
    void admitsUpToLimit() {
        ConvertConcurrencyLimit limit = limit(2);

        assertThat(limit.tryEnter()).isTrue();
        assertThat(limit.tryEnter()).isTrue();
        assertThat(limit.tryEnter()).isFalse();
        limit.exit();
        assertThat(limit.tryEnter()).isTrue();
    }

    @Test
    @DisplayName("거절이 끝내는 수보다 많이 쌓이면 Retry-After를 늘리고(최대까지), 거절이 없으면 가장 짧은 값으로 돌아간다")
    void retryAfterFollowsWaitingUsers() throws InterruptedException {
        ConvertConcurrencyLimit limit = limit(1);
        assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(limit.tryEnter()).isTrue();
        for (int i = 0; i < 50; i++) {
            assertThat(limit.tryEnter()).isFalse();
        }

        Thread.sleep(1_100);

        // 창 동안 초당 거절 약 45 × Retry-After 1초 = 기다리는 회원 약 45명, 끝낸 수 0(최소 1로 봄) → 45초지만 최대 10초
        assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(10));

        limit.exit();
        Thread.sleep(1_100);

        assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }
}
