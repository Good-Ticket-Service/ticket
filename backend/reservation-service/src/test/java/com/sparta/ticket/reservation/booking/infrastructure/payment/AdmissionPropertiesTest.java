package com.sparta.ticket.reservation.booking.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AdmissionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PaymentConfirmConfig.class)
            .withPropertyValues(
                    "reservation.booking.payment-confirm.window=5m",
                    "reservation.booking.payment-confirm.grace=1m",
                    "reservation.booking.payment-confirm.fallback.unsent-after=5s",
                    "reservation.booking.payment-polling.base=1s",
                    "reservation.booking.payment-polling.max=5s",
                    "reservation.booking.payment-polling.busy-above=1000",
                    "reservation.booking.admission.enabled=true",
                    "reservation.booking.admission.late-after=5s",
                    "reservation.booking.admission.shed-above=50",
                    "reservation.booking.admission.resume-below=10",
                    "reservation.booking.admission.trend-window=10s",
                    "reservation.booking.admission.retry-after=5s",
                    "reservation.booking.admission.max-retry-after=30s",
                    "reservation.booking.admission.stale-after=10s",
                    "reservation.booking.admission.priority-window=2m",
                    "reservation.booking.admission.priority-rate=10");

    @Test
    @DisplayName("설정 값을 그대로 읽는다")
    void bindsValues() {
        runner.run(context -> {
            AdmissionProperties properties = context.getBean(AdmissionProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.lateAfter()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.shedAbove()).isEqualTo(50);
            assertThat(properties.resumeBelow()).isEqualTo(10);
            assertThat(properties.trendWindow()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.maxRetryAfter()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.priorityRate()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("늦은 결제 기준 시간이 빠지면 기동이 실패한다")
    void requiresLateAfter() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PaymentConfirmConfig.class)
                .withPropertyValues(
                        "reservation.booking.payment-confirm.window=5m",
                        "reservation.booking.payment-confirm.grace=1m",
                        "reservation.booking.payment-confirm.fallback.unsent-after=5s",
                        "reservation.booking.payment-polling.base=1s",
                        "reservation.booking.payment-polling.max=5s",
                        "reservation.booking.payment-polling.busy-above=1000",
                        "reservation.booking.admission.shed-above=50",
                        "reservation.booking.admission.trend-window=10s",
                        "reservation.booking.admission.retry-after=5s",
                        "reservation.booking.admission.max-retry-after=30s",
                        "reservation.booking.admission.stale-after=10s",
                        "reservation.booking.admission.priority-window=2m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("거절 기준이 0이면 기동이 실패한다")
    void rejectsZeroShedAbove() {
        runner.withPropertyValues("reservation.booking.admission.shed-above=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("다시 받는 기준이 거절 기준보다 작지 않으면 경계에서 오가므로 기동이 실패한다")
    void rejectsResumeNotBelowShed() {
        runner.withPropertyValues("reservation.booking.admission.resume-below=50")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("증가를 보는 창이 2초보다 짧으면 1초 주기 집계가 절반마다 들어가지 못해 기동이 실패한다")
    void rejectsTooShortTrendWindow() {
        runner.withPropertyValues("reservation.booking.admission.trend-window=1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Retry-After 최대가 가장 짧은 값보다 짧으면 기동이 실패한다")
    void rejectsMaxRetryAfterBelowRetryAfter() {
        runner.withPropertyValues("reservation.booking.admission.max-retry-after=4s")
                .run(context -> assertThat(context).hasFailed());
    }
}
