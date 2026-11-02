package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 만료·결과 확인·취소 확인·회차 취소 정리 스케줄러 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다. */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.scheduler")
public record BookingSchedulerProperties(
        @Valid @NotNull HoldExpiry holdExpiry,
        @Valid @NotNull PaymentReconcile paymentReconcile,
        @Valid @NotNull CancelReconcile cancelReconcile,
        @Valid @NotNull ScheduleCancel scheduleCancel) {

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

    public record ScheduleCancel(
            @NotNull Duration interval,
            @Positive int batchSize,
            // 0이면 커밋 전이던 결제 시작을 기다리지 않고 완료해, 그 결제가 성공해도 환불되지 않는다
            @NotNull @DurationMin(seconds = 1) Duration completeAfter,
            @NotNull Duration timeBudget) {}
}
