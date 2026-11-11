package com.sparta.ticket.payment.domain.model.settlement.projection;

/** Kafka 출력과 DB sink 사이의 절대값·검증 완료 메시지. */
public record ProjectionResult(
        String buildId,
        String type,
        String key,
        long revision,
        SettlementTotals totals,
        String factKey,
        String meaningHash,
        String error,
        long amount) {
    public static ProjectionResult failure(String buildId, String key, String error, long amount) {
        return new ProjectionResult(buildId, "ERROR", key, 0, null, key, null, error, amount);
    }

    public String hash() {
        return SettlementFact.hash(buildId + "|" + type + "|" + key + "|" + revision + "|" + totals + "|" + factKey
                + "|" + meaningHash + "|" + error + "|" + amount);
    }
}
