package com.sparta.ticket.fakepg.domain.model.fault;

/**
 * PG API 하나(제공자 × 동작)에 거는 장애 설정. 모든 값의 기본은 "장애 없음"이다. 비율은 0~1 사이 확률이다.
 *
 * @param latencyMs 처리 전에 기다리는 시간. PG 지연을 흉내 낸다
 * @param serverErrorRate 처리하지 않고 500을 돌려줄 확률. 요청이 PG에서 처리되지 않은 5xx다
 * @param failRate 업무 거절(승인 거절, 취소 불가) 확률
 * @param responseLossRate 처리와 기록을 끝낸 뒤 응답만 돌려주지 않을 확률. holdMs만큼 붙잡았다가 500을 보내,
 *     클라이언트는 읽기 타임아웃이나 5xx로 결과를 모르게 된다
 * @param delayedApprovalRate 승인을 바로 기록하지 않고 delayedApprovalMs 뒤에 기록할 확률(승인만 해당).
 *     응답은 응답 유실과 같이 붙잡았다가 500을 보낸다. 그동안 조회하면 아직 READY다(늦은 승인)
 * @param delayedApprovalMs 지연 승인을 기록하기까지의 시간
 * @param holdMs 응답 유실·지연 승인 때 응답을 붙잡는 시간. 클라이언트 읽기 타임아웃보다 길게 둔다
 * @param rateLimitPerSecond 초당 처리 한도. 넘으면 처리하지 않고 429. 0은 무제한
 */
public record FaultConfig(
        long latencyMs,
        double serverErrorRate,
        double failRate,
        double responseLossRate,
        double delayedApprovalRate,
        long delayedApprovalMs,
        long holdMs,
        int rateLimitPerSecond) {

    public static final long DEFAULT_HOLD_MS = 15_000;

    public static FaultConfig none() {
        return new FaultConfig(0, 0, 0, 0, 0, 0, DEFAULT_HOLD_MS, 0);
    }

    public FaultConfig {
        requireNotNegative(latencyMs, "latencyMs");
        requireNotNegative(delayedApprovalMs, "delayedApprovalMs");
        requireNotNegative(holdMs, "holdMs");
        requireNotNegative(rateLimitPerSecond, "rateLimitPerSecond");
        requireRate(serverErrorRate, "serverErrorRate");
        requireRate(failRate, "failRate");
        requireRate(responseLossRate, "responseLossRate");
        requireRate(delayedApprovalRate, "delayedApprovalRate");
    }

    private static void requireNotNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + "는 0 이상이어야 한다");
        }
    }

    private static void requireRate(double value, String name) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + "는 0~1 사이여야 한다");
        }
    }
}
