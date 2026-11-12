package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 결제 결과 소비자 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다. 코드에 기본값을 두면 키가 빠졌을 때
 * 조용히 1스레드로 떠서 결제 중 예약이 쌓이므로, 빠지면 기동이 실패하게 한다.
 *
 * @param concurrency 동시 소비 스레드 수. DB가 포화하면 반영 한 건이 느려져 스레드 하나로는 결과가 밀린다. 파티션 수(12)를 넘는 만큼은 놀기만 한다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.payment-result")
public record PaymentResultProperties(@Positive int concurrency) {}
