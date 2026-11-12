package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 만료·결과 확인·취소 확인·회차 취소 정리·Outbox 발행·결제 중 집계 스케줄러 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 * 시간 값은 0을 받지 않는다. 간격이 0이면 쉬지 않고 돌고, 시간 예산이 0이면 첫 건도 처리하지 않고 끊기 때문이다.
 */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.scheduler")
public record BookingSchedulerProperties(
        @Valid @NotNull HoldExpiry holdExpiry,
        @Valid @NotNull PaymentReconcile paymentReconcile,
        @Valid @NotNull CancelReconcile cancelReconcile,
        @Valid @NotNull ScheduleCancel scheduleCancel,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull PaymentBacklog paymentBacklog) {

    public record HoldExpiry(
            @NotNull @DurationMin(millis = 1) Duration interval,
            @Positive int batchSize,
            @NotNull @DurationMin(millis = 1) Duration timeBudget) {}

    public record PaymentReconcile(
            @NotNull @DurationMin(millis = 1) Duration interval,
            @NotNull @DurationMin(millis = 1) Duration threshold,
            @NotNull @DurationMin(millis = 1) Duration stuckAfter,
            @Positive int batchSize,
            @NotNull @DurationMin(millis = 1) Duration timeBudget) {}

    public record CancelReconcile(
            @NotNull @DurationMin(millis = 1) Duration interval,
            @NotNull @DurationMin(millis = 1) Duration threshold,
            @Positive int batchSize,
            @NotNull @DurationMin(millis = 1) Duration timeBudget) {}

    public record ScheduleCancel(
            @NotNull @DurationMin(millis = 1) Duration interval,
            @Positive int batchSize,
            // 0이면 커밋 전이던 결제 시작을 기다리지 않고 완료해, 그 결제가 성공해도 환불되지 않는다
            @NotNull @DurationMin(seconds = 1) Duration completeAfter,
            @NotNull @DurationMin(millis = 1) Duration timeBudget) {}

    /**
     * @param interval 발행 사이에 쉬는 시간. 메시지로 요청한 승인이 payment에 닿기까지 더해지는 지연이다
     * @param sendTimeout 한 메시지를 브로커가 받았다고 답하기까지 기다리는 시간. 넘으면 보내지 못한 것으로 보고 남긴다
     */
    public record Outbox(
            @NotNull @DurationMin(millis = 1) Duration interval,
            @Positive int batchSize,
            @NotNull @DurationMin(millis = 1) Duration sendTimeout) {}

    /** @param interval 결제 중 예약 수를 다시 세는 간격. 폴링 간격이 이 값만큼 늦게 반응한다 */
    public record PaymentBacklog(
            @NotNull @DurationMin(millis = 1) Duration interval) {}
}
