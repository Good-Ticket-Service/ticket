package com.sparta.ticket.reservation.seat.application.hold;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 좌석 선점 설정.
 *
 * @param duration 선점 유지 시간(#248). Notion API 명세서 「좌석 선점」의 계약값(선점 시각 + 10분)이라 코드 기본값을 10분으로 두고
 *     config-repo·docker-compose.yml·.env.example에는 넣지 않는다. 부하 측정에서 만료와 재선점을 짧게 재현할 때만 측정용 환경 변수
 *     (RESERVATION_SEAT_HOLD_DURATION)로 바꾸고, 운영 정책을 바꾸는 용도로 쓰지 않는다
 * @param mode 선점을 어디에 두는지(#449). DB는 선점 때 예약을 만드는 지금 방식, REDIS는 선점을 Redis에 두고 결제하기 때 예약을 만든다.
 *     효과를 잰 뒤 켜려고 기본은 DB다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.seat.hold")
public record SeatHoldProperties(
        @NotNull @DurationMin(seconds = 1) @DefaultValue("10m")
        Duration duration,

        @NotNull @DefaultValue("DB") SeatHoldMode mode) {}
