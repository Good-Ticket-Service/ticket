package com.sparta.ticket.payment.infrastructure.pg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PG별 서킷 브레이커({@code pg.circuit-breaker.*}). 멈춘 PG만 막고 느리지만 응답하는 PG는 기다리게 하는 것이 목적이다.
 * 실패는 응답을 못 받은 호출(읽기 타임아웃·연결 끊김), 연결 실패, 5xx다. 업무 거절(4xx)과 429는 세지 않는다.
 *
 * @param enabled 끄면 판단 없이 그대로 부른다
 * @param failureRatePercent 판단 창에서 실패가 이 비율 이상이면 연다
 * @param slowCallDuration 이 시간을 넘긴 호출은 성공해도 느린 호출로 센다. 2~3초 지연은 기다리고 그보다 훨씬 느리면 멈춘 것으로 본다
 * @param slowCallRatePercent 판단 창에서 느린 호출이 이 비율 이상이면 연다
 * @param windowSize 최근 몇 건으로 판단할지
 * @param minimumCalls 이만큼 쌓이기 전에는 열지 않는다
 * @param openDuration 연 뒤 막아 두는 시간. 지나면 시험 호출을 보낸다
 * @param halfOpenCalls 닫을지 정하는 시험 호출 수
 * @param halfOpenMaxWait 시험 호출 결과가 이 시간 안에 다 오지 않으면 다시 연다. PG 호출 최대 대기(연결+읽기)보다 길게 둔다
 */
@ConfigurationProperties("pg.circuit-breaker")
public record PgCircuitBreakerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("50") int failureRatePercent,
        @DefaultValue("5s") Duration slowCallDuration,
        @DefaultValue("80") int slowCallRatePercent,
        @DefaultValue("50") int windowSize,
        @DefaultValue("20") int minimumCalls,
        @DefaultValue("10s") Duration openDuration,
        @DefaultValue("5") int halfOpenCalls,
        @DefaultValue("30s") Duration halfOpenMaxWait) {

    public PgCircuitBreakerProperties {
        if (failureRatePercent < 1
                || failureRatePercent > 100
                || slowCallRatePercent < 1
                || slowCallRatePercent > 100) {
            throw new IllegalArgumentException("서킷 브레이커 비율은 1~100이어야 한다");
        }
        if (windowSize < 1 || minimumCalls < 1 || halfOpenCalls < 1) {
            throw new IllegalArgumentException("서킷 브레이커 건수는 1 이상이어야 한다");
        }
        if (!positive(slowCallDuration) || !positive(openDuration) || !positive(halfOpenMaxWait)) {
            throw new IllegalArgumentException("서킷 브레이커 시간은 0보다 커야 한다");
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
