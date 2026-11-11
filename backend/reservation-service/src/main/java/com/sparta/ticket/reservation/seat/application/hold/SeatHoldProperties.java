package com.sparta.ticket.reservation.seat.application.hold;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 좌석 선점 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다. 값이 없으면 기동이 멈춘다.
 *
 * @param lockMode 선점이 좌석을 잠글 때 앞선 트랜잭션을 기다리는지(wait) 바로 409로 끝내는지(nowait)(#313).
 *     booking 경로(결제 확정·만료·선점 해제)는 이 값과 관계없이 기다린다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.seat.hold")
public record SeatHoldProperties(@NotNull LockMode lockMode) {

    public enum LockMode {
        WAIT,
        NOWAIT
    }
}
