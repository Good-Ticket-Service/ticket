package com.sparta.ticket.payment.domain.model.settlement.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 한 결제의 판매·완료 환불을 검증한다. 환불 선행은 영속 상태로 보류한다. */
public record PaymentProjectionState(
        SettlementFact sale, SettlementFact refund, boolean saleApplied, boolean refundApplied) {
    public static PaymentProjectionState empty() {
        return new PaymentProjectionState(null, null, false, false);
    }

    public record Decision(PaymentProjectionState state, List<SettlementFact> contributions, String error) {}

    public Decision accept(SettlementFact fact) {
        SettlementFact existing = fact.kind() == SettlementFact.Kind.SALE ? sale : refund;
        if (existing != null) {
            return new Decision(
                    this, List.of(), existing.meaningHash().equals(fact.meaningHash()) ? null : "BUSINESS_CONFLICT");
        }
        SettlementFact nextSale = fact.kind() == SettlementFact.Kind.SALE ? fact : sale;
        SettlementFact nextRefund = fact.kind() == SettlementFact.Kind.REFUND ? fact : refund;
        if (nextSale != null
                && nextRefund != null
                && (nextSale.paymentId() != nextRefund.paymentId()
                        || nextSale.sellerId() != nextRefund.sellerId()
                        || nextSale.scheduleId() != nextRefund.scheduleId()
                        || nextSale.sellerAmount() != nextRefund.sellerAmount()
                        || !Objects.equals(nextSale.policyVersion(), nextRefund.policyVersion())
                        || nextRefund.occurredAt().isBefore(nextSale.occurredAt()))) {
            return new Decision(this, List.of(), "REFUND_SOURCE_CONFLICT");
        }
        List<SettlementFact> contributions = new ArrayList<>();
        boolean applySale = nextSale != null;
        boolean applyRefund = applySale && nextRefund != null;
        if (applySale && !saleApplied) contributions.add(nextSale);
        if (applyRefund && !refundApplied) contributions.add(nextRefund);
        return new Decision(
                new PaymentProjectionState(nextSale, nextRefund, applySale, applyRefund),
                List.copyOf(contributions),
                null);
    }

    public boolean pending() {
        return refund != null && !refundApplied;
    }
}
