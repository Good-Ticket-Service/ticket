package com.sparta.ticket.reservation.seat.application.remaining;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 등급별 잔여석 조회 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 * 측정하면서 바꿀 값이라 코드에 기본값을 두지 않는다. 값이 없으면 기동이 멈춘다.
 *
 * @param cacheTtl 집계 결과를 서버 메모리에 두는 시간. 지나면 다음 요청이 다시 집계한다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.seat.remaining")
public record RemainingSeatProperties(@NotNull Duration cacheTtl) {}
