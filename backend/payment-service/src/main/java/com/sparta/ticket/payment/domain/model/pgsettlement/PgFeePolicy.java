package com.sparta.ticket.payment.domain.model.pgsettlement;

import java.time.LocalDateTime;

/** PG 계약 한 버전. 취소 수수료는 취소일 요율이 아닌 원승인 요율로 반환한다. */
public record PgFeePolicy(
        String provider, String version, LocalDateTime effectiveFrom, int rateBp, int vatRateBp, int businessDays) {
    public PgFeePolicy {
        if (!("TOSS".equals(provider) || "KAKAO".equals(provider))
                || version == null
                || version.isBlank()
                || version.length() > 40
                || effectiveFrom == null
                || rateBp < 0
                || rateBp > 5000
                || vatRateBp < 0
                || vatRateBp > 10000
                || businessDays < 1
                || businessDays > 20) throw new IllegalArgumentException("PG 수수료·정산 주기 설정이 올바르지 않다");
    }

    public long fee(long amount) {
        long supply = Math.multiplyExact(amount, rateBp) / 10000;
        return Math.addExact(supply, Math.multiplyExact(supply, vatRateBp) / 10000);
    }
}
