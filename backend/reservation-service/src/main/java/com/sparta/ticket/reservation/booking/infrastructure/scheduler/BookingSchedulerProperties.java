package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 만료·결과 확인·취소 확인 스케줄러 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다. */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.scheduler")
public record BookingSchedulerProperties(
        @Valid @NotNull HoldExpiry holdExpiry,
        @Valid @NotNull PaymentReconcile paymentReconcile,
        @Valid @NotNull CancelReconcile cancelReconcile) {

    public record HoldExpiry(
            @NotNull Duration interval,
            @Positive int batchSize,
            @NotNull Duration timeBudget) {}

    public record PaymentReconcile(
            @NotNull Duration interval,
            @NotNull Duration threshold,
            @NotNull Duration stuckAfter,
            @Positive int batchSize,
            @NotNull Duration timeBudget) {}

    public record CancelReconcile(
            @NotNull Duration interval,
            @NotNull Duration threshold,
            @Positive int batchSize,
            @NotNull Duration timeBudget) {}
}
