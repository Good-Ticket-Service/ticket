package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 한 시점부터 적용하는 정산 요율. 승인 시각으로 버전을 골라 정산 항목에 고정하고, 나중에 요율이 바뀌어도 과거 거래를 다시 계산하지 않는다.
 * 요율은 만분율(bp)이다. 1200bp = 12%.
 *
 * @param commissionRateBp 정가에 매기는 판매대행 수수료율(VAT 별도)
 * @param vatRateBp 수수료 VAT율. 예매수수료(VAT 포함)의 순수입 계산에도 쓴다
 * @param pgRateBp 예상 PG 수수료율. 원장에 넣지 않고 손익 조회에만 쓴다
 * @param pgVatRateBp 예상 PG 수수료의 VAT율
 * @param pgFeeRefundedOnCancel 전액 취소 때 예상 PG 수수료를 돌려받는다고 볼지
 * @param eligibleBusinessDays 회차 시작일 다음 날부터 세는 정산 가능 영업일
 */
public record SettlementPolicy(
        String version,
        LocalDateTime effectiveFrom,
        int commissionRateBp,
        int vatRateBp,
        int pgRateBp,
        int pgVatRateBp,
        boolean pgFeeRefundedOnCancel,
        int eligibleBusinessDays) {

    static final int BASIS = 10_000;

    public SettlementPolicy {
        if (version == null || version.isBlank() || version.length() > 40)
            throw new IllegalArgumentException("정산 정책 버전은 1~40자다");
        Objects.requireNonNull(effectiveFrom, "정산 정책 적용 시작 시각이 없다");
        requireRate(commissionRateBp, "판매대행 수수료율");
        requireRate(vatRateBp, "VAT율");
        requireRate(pgRateBp, "PG 수수료율");
        requireRate(pgVatRateBp, "PG 수수료 VAT율");
        // Note: 수수료와 그 VAT를 합쳐 정가를 넘으면 판매자 몫이 음수가 된다. 설정 단계에서 막는다.
        if ((long) commissionRateBp * (BASIS + vatRateBp) > (long) BASIS * BASIS)
            throw new IllegalArgumentException("수수료와 VAT 합이 정가의 100%를 넘는다: " + version);
        if (eligibleBusinessDays < 1 || eligibleBusinessDays > 30)
            throw new IllegalArgumentException("정산 가능 영업일은 1~30일이다: " + version);
    }

    private static void requireRate(int rateBp, String name) {
        if (rateBp < 0 || rateBp > BASIS) throw new IllegalArgumentException(name + "은 0~10000bp다");
    }
}
