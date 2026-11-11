package com.sparta.ticket.payment.domain.model.settlement.projection;

/** 조회용 기간 순변동. 미지급·현금·지급 가능액이 아니다. */
public record SettlementTotals(long saleCount, long saleSellerAmount, long refundCount, long refundSellerAmount) {
    public static SettlementTotals empty() {
        return new SettlementTotals(0, 0, 0, 0);
    }

    public SettlementTotals add(SettlementFact fact) {
        return fact.kind() == SettlementFact.Kind.SALE
                ? new SettlementTotals(
                        Math.addExact(saleCount, 1),
                        Math.addExact(saleSellerAmount, fact.sellerAmount()),
                        refundCount,
                        refundSellerAmount)
                : new SettlementTotals(
                        saleCount,
                        saleSellerAmount,
                        Math.addExact(refundCount, 1),
                        Math.addExact(refundSellerAmount, fact.sellerAmount()));
    }

    public long netSellerAmount() {
        return Math.subtractExact(saleSellerAmount, refundSellerAmount);
    }
}
