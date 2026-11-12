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
 * @param lockMode 선점이 좌석을 잠글 때 앞선 트랜잭션을 기다리는지(wait) 바로 409로 끝내는지(nowait)(#313).
 *     booking 경로(결제 확정·만료·선점 해제)는 이 값과 관계없이 기다린다. 값은 config-repo/reservation-service.yml 한 곳에만 두고,
 *     값이 없으면 기동이 멈춘다
 * @param duration 선점 유지 시간(#248). Notion API 명세서 「좌석 선점」의 계약값(선점 시각 + 10분)이라 코드 기본값을 10분으로 두고
 *     config-repo·docker-compose.yml·.env.example에는 넣지 않는다. 부하 측정에서 만료와 재선점을 짧게 재현할 때만 측정용 환경 변수
 *     (RESERVATION_SEAT_HOLD_DURATION)로 바꾸고, 운영 정책을 바꾸는 용도로 쓰지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.seat.hold")
public record SeatHoldProperties(
        @NotNull LockMode lockMode,

        @NotNull @DurationMin(seconds = 1) @DefaultValue("10m")
        Duration duration) {

    public enum LockMode {
        WAIT,
        NOWAIT
    }
}
