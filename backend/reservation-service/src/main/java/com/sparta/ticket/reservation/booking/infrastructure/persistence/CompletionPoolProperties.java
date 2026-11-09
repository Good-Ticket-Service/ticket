package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 결제를 끝내는 쪽이 쓰는 전용 DB 연결 풀 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 *
 * @param maximumPoolSize 전용 풀의 연결 수. 결과 소비자·Outbox 발행기·결제 중 집계가 나눠 쓴다. 사용자 요청 풀과 합쳐 DB 연결 한도 안이어야 한다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.completion-pool")
public record CompletionPoolProperties(@Positive int maximumPoolSize) {}
