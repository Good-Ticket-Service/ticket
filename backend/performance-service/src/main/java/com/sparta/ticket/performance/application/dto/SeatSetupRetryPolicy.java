package com.sparta.ticket.performance.application.dto;

import java.time.Duration;

/**
 * 좌석 준비 자동 재시도 설정. 값은 config-repo의 performance.seat-setup.*에서 정한다(SeatSetupConfig).
 *
 * @param maxAttempts 이 횟수만큼 보내고도 일시 실패면 FAILED
 * @param firstDelay 첫 시도 뒤 다음 시도까지의 간격. 시도마다 두 배가 된다. 한 번 호출의 최대 시간보다 길어야 한다(임대 시간)
 * @param maxDelay 간격의 상한
 */
public record SeatSetupRetryPolicy(int maxAttempts, Duration firstDelay, Duration maxDelay) {

    /** attempt번째 시도(1부터)를 시작할 때 다음 시도 시각까지의 간격. */
    public Duration delayAfter(int attempt) {
        Duration delay = firstDelay.multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
