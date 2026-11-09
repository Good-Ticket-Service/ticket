package com.sparta.ticket.reservation.seat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 좌석 배치도 캐시 설정 검사(#311). cache-max-age가 cache-refresh보다 길지 않거나 값이 없으면 기동이 멈춘다. */
class SelectableSeatCacheConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(SelectableSeatCacheConfig.class);

    @Test
    @DisplayName("cache-max-age가 cache-refresh보다 길면 기동한다")
    void startsWhenMaxAgeLongerThanRefresh() {
        runner.withPropertyValues(
                        "reservation.seat.selectable.cache-refresh=1s", "reservation.seat.selectable.cache-max-age=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SelectableSeatProperties.class).cacheMaxAge())
                            .hasSeconds(5);
                    assertThat(context).hasSingleBean(SeatSnapshotRefreshExecutor.class);
                });
    }

    @Test
    @DisplayName("cache-max-age가 cache-refresh와 같으면 기동이 실패한다")
    void failsWhenMaxAgeEqualsRefresh() {
        runner.withPropertyValues(
                        "reservation.seat.selectable.cache-refresh=1s", "reservation.seat.selectable.cache-max-age=1s")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("cache-max-age"));
    }

    @Test
    @DisplayName("cache-max-age가 cache-refresh보다 짧으면 기동이 실패한다")
    void failsWhenMaxAgeShorterThanRefresh() {
        runner.withPropertyValues(
                        "reservation.seat.selectable.cache-refresh=5s", "reservation.seat.selectable.cache-max-age=1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("코드 기본값이 없어 값이 빠지면 기동이 실패한다")
    void failsWithoutValues() {
        runner.withPropertyValues("reservation.seat.selectable.cache-refresh=1s")
                .run(context -> assertThat(context).hasFailed());
    }
}
