package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SaleSource;
import com.sparta.ticket.payment.domain.model.settlement.SettlementFailure;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import lombok.extern.slf4j.Slf4j;

/** 승인 포착과 보충이 같은 방식으로 SALE을 계산하게 한다. */
@Slf4j
final class SaleCalculation {

    private SaleCalculation() {}

    /** DB를 건드리지 않는 순수 계산이라, 예상하지 못한 예외도 트랜잭션을 깨지 않고 UNPOSTED로 바꿀 수 있다. */
    static SettlementItem calculate(SettlementRules rules, SaleSource source) {
        try {
            return rules.saleFor(source);
        } catch (RuntimeException e) {
            log.error("정산 계산 중 예상하지 못한 오류. paymentId={}", source.paymentId(), e);
            return SettlementItem.unpostedSale(source, SettlementFailure.UNEXPECTED_ERROR);
        }
    }
}
