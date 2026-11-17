package com.sparta.ticket.reservation.seat.application.hold;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 좌석 선택 완료(Redis 선점을 예약으로 바꾸기) 동시 처리 상한(#459).
 *
 * @param maxConcurrent 동시에 처리하는 좌석 선택 완료 수. 0이면 제한하지 않는다(기본). 기준: 기본 연결 풀 크기 × (좌석 선택 완료 시간 ÷
 *     그중 DB 연결을 쥐는 시간), 요청 스레드의 1/3 이하. 측정(풀 30)에서 좌석 선택 완료 약 80ms 중 DB 약 40ms라 풀 30이면 약 60이다.
 *     평상시 처리량보다 작으면 거절된 요청이 다시 오며 재시도가 몰린다(측정: 상한 30에서 거절 초당 약 3,600건)
 * @param retryAfter 거절 응답의 가장 짧은 Retry-After
 * @param maxRetryAfter 기다리는 회원이 많을 때 늘린 Retry-After의 최대
 */
@Validated
@ConfigurationProperties(prefix = "reservation.seat.hold.convert-limit")
public record ConvertLimitProperties(
        @PositiveOrZero @DefaultValue("0") int maxConcurrent,

        @NotNull @DurationMin(seconds = 1) @DefaultValue("1s")
        Duration retryAfter,

        @NotNull @DurationMin(seconds = 1) @DefaultValue("10s")
        Duration maxRetryAfter) {}
