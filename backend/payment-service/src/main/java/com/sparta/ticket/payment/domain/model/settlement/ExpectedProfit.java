package com.sparta.ticket.payment.domain.model.settlement;

/**
 * 거래 1건의 예상 PG 비용과 예상 플랫폼 몫. 정책 요율로 추정한 값이라 원장에 넣지 않고 손익 조회 때만 계산한다.
 * 실제 PG 청구액이나 세무상 확정 손익이 아니다.
 *
 * @param pgFee 예상 PG 비용 C = floor(E × PG율) + floor(그 값 × PG VAT율)
 * @param platformAmount 예상 플랫폼 몫 H = F + Bn − P − C. 음수(적자)도 그대로 보여 준다
 */
public record ExpectedProfit(long pgFee, long platformAmount) {

    /** @param canceled 전액 환불로 거래가 상쇄됐는지. 수수료 수익은 사라지고 PG 비용은 정책의 반환 여부를 따른다 */
    public static ExpectedProfit of(SaleAmounts amounts, SettlementPolicy policy, boolean canceled) {
        long pgFee = (long) amounts.paid() * policy.pgRateBp() / SettlementPolicy.BASIS;
        pgFee += pgFee * policy.pgVatRateBp() / SettlementPolicy.BASIS;
        if (canceled) {
            long cost = policy.pgFeeRefundedOnCancel() ? 0 : pgFee;
            return new ExpectedProfit(cost, -cost);
        }
        long platformAmount = (long) amounts.commission() + amounts.bookingFeeNet() - amounts.discount() - pgFee;
        return new ExpectedProfit(pgFee, platformAmount);
    }
}
