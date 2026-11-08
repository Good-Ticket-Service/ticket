package com.sparta.ticket.reservation.queue.application;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 대기열 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 * 측정하면서 바꿀 값이라 코드에 기본값을 두지 않는다. 값이 없으면 기동이 멈춘다.
 *
 * @param admitPerSecond 초당 입장 인원. 예상 대기 시간 계산에 쓰고, 입장 스케줄러가 같은 값으로 들여보낸다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.queue")
public record QueueProperties(@Positive int admitPerSecond) {}
