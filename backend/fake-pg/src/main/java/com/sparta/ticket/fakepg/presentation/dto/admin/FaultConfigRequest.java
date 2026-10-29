package com.sparta.ticket.fakepg.presentation.dto.admin;

import com.sparta.ticket.fakepg.domain.model.fault.FaultConfig;
import com.sparta.ticket.fakepg.domain.model.fault.TamperMode;

/**
 * 장애 설정 요청. 비운 항목은 "장애 없음" 값이 된다(holdMs만 기본 15초). 항목 설명은 {@link FaultConfig}에 있다.
 */
public record FaultConfigRequest(
        Long latencyMs,
        Double serverErrorRate,
        Double failRate,
        Double responseLossRate,
        Double delayedApprovalRate,
        Long delayedApprovalMs,
        Long holdMs,
        Integer rateLimitPerSecond,
        Double tamperRate,
        TamperMode tamperMode) {

    public FaultConfig toConfig() {
        return new FaultConfig(
                orZero(latencyMs),
                orZero(serverErrorRate),
                orZero(failRate),
                orZero(responseLossRate),
                orZero(delayedApprovalRate),
                orZero(delayedApprovalMs),
                holdMs == null ? FaultConfig.DEFAULT_HOLD_MS : holdMs,
                rateLimitPerSecond == null ? 0 : rateLimitPerSecond,
                orZero(tamperRate),
                tamperMode);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }
}
