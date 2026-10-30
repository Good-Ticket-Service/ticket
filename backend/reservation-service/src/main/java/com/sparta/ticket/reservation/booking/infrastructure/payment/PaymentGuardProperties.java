package com.sparta.ticket.reservation.booking.infrastructure.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자 요청의 결제 호출 보호 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 *
 * @param enabled 끄면 막지 않고 기록도 하지 않는다. 대기열처럼 끄고 켜며 같은 조건으로 측정을 비교하고, 운영에서 잘못 막을 때 끄는 스위치다
 * @param maxConcurrentCalls 동시에 기다릴 수 있는 결제 호출 수(Bulkhead). 넘으면 기다리지 않고 바로 거절한다.
 *     Tomcat 스레드의 일부만 결제 대기에 쓰게 해 나머지 API가 스레드를 얻게 한다
 * @param windowSize Circuit Breaker가 최근 몇 번의 호출로 판단할지
 * @param minimumCalls 이만큼 호출이 쌓이기 전에는 열지 않는다
 * @param failureRatePercent 응답 없음 비율이 이 값 이상이면 연다
 * @param openDuration 연 뒤 이만큼 막고, 그 뒤 몇 번만 시험 삼아 보낸다
 * @param halfOpenCalls 시험 삼아 보내는 호출 수. 이 결과로 닫을지 다시 열지 정한다
 * @param halfOpenMaxWait 시험 호출 결과가 이 시간 안에 다 오지 않으면 다시 연다. 결과가 기록되지 않은 허가가 시험 자리를 영영
 *     차지하지 않게 하는 안전망이라, 결제 호출의 최대 대기(connect + read)보다 길게 둔다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.payment-guard")
public record PaymentGuardProperties(
        boolean enabled,
        @Positive int maxConcurrentCalls,
        @Positive int windowSize,
        @Positive int minimumCalls,
        @Positive @Max(100) float failureRatePercent,
        @NotNull Duration openDuration,
        @Positive int halfOpenCalls,
        @NotNull Duration halfOpenMaxWait) {}
