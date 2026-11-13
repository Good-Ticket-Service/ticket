package com.sparta.ticket.reservation.seat.application.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 선점 유지 시간 설정 검사(#248). 값이 없으면 계약값 10분이고, 0 이하면 기동이 멈춘다. */
class SeatHoldPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SeatHoldDuration.class);

    @Test
    @DisplayName("유지 시간 값이 없으면 계약값 10분이다")
    void defaultsToTenMinutes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SeatHoldDuration.class).current()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    @DisplayName("측정용으로 준 유지 시간을 그대로 쓴다")
    void usesGivenDuration() {
        runner.withPropertyValues("reservation.seat.hold.duration=90s")
                .run(context -> assertThat(
                                context.getBean(SeatHoldDuration.class).current())
                        .isEqualTo(Duration.ofSeconds(90)));
    }

    @Test
    @DisplayName("유지 시간이 0이면 기동이 실패한다")
    void failsWhenZero() {
        runner.withPropertyValues("reservation.seat.hold.duration=0s")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(BindValidationException.class)
                        .hasMessageContaining("duration"));
    }

    @Test
    @DisplayName("유지 시간이 음수면 기동이 실패한다")
    void failsWhenNegative() {
        runner.withPropertyValues("reservation.seat.hold.duration=-1m")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(BindValidationException.class)
                        .hasMessageContaining("duration"));
    }
}
