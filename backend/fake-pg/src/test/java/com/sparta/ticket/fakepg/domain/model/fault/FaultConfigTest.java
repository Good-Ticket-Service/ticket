package com.sparta.ticket.fakepg.domain.model.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LongSummaryStatistics;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaultConfigTest {

    private static FaultConfig latency(long min, long max) {
        return new FaultConfig(min, 0, 0, 0, 0, 0, FaultConfig.DEFAULT_HOLD_MS, 0, 0, null, max);
    }

    @Test
    @DisplayName("상한이 0이면 지연은 latencyMs로 고정이다")
    void fixedWhenNoMax() {
        assertThat(latency(300, 0).drawLatencyMs(new SplittableRandom(1))).isEqualTo(300);
    }

    @Test
    @DisplayName("범위를 주면 양 끝을 포함한 균등 분포에서 뽑아 평균이 가운데에 온다")
    void uniformWithinRange() {
        FaultConfig config = latency(100, 500);
        SplittableRandom random = new SplittableRandom(20261105);
        LongSummaryStatistics stats = LongStream.range(0, 100_000)
                .map(i -> config.drawLatencyMs(random))
                .summaryStatistics();

        assertThat(stats.getMin()).isEqualTo(100);
        assertThat(stats.getMax()).isEqualTo(500);
        assertThat(stats.getAverage()).isBetween(298.0, 302.0);
    }

    @Test
    @DisplayName("상한이 하한보다 작으면 만들 수 없다")
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> latency(500, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(latency(300, 300).drawLatencyMs(new SplittableRandom(1))).isEqualTo(300);
    }
}
