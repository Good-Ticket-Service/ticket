package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.SaleSource;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인 DONE을 정산 항목(SALE)과 판매 분개로 남긴다(Task 20-3, settlement-design 4.2절).
 * 결제 승인 트랜잭션 안에서만 부르며, 정산 계산이 실패해도 결제 승인을 막지 않는다.
 */
@Slf4j
@Component
public class SettlementCaptureTransactions {

    private final SettlementRules rules;
    private final SettlementItemRepository items;
    private final LedgerTransactions ledger;
    private final CommittedObservation observation;
    private final boolean enabled;

    public SettlementCaptureTransactions(
            SettlementRules rules,
            SettlementItemRepository items,
            LedgerTransactions ledger,
            CommittedObservation observation,
            @Value("${payment.settlement.capture-enabled:false}") boolean enabled) {
        this.rules = rules;
        this.items = items;
        this.ledger = ledger;
        this.observation = observation;
        this.enabled = enabled;
    }

    /**
     * 승인된 결제 1건의 SALE을 만든다. 같은 결제의 SALE이 이미 있으면 아무것도 하지 않는다.
     * Note: 호출자가 payment 행(있으면 refund 행까지)을 잠근 트랜잭션에서만 부른다. 그래서 SALE 존재 확인과 INSERT 사이에 경쟁자가 없다.
     * 계산 실패는 UNPOSTED로 남기고 결제는 그대로 커밋한다. DB 오류는 잡지 않고 결제 트랜잭션과 함께 롤백해 recovery에 맡긴다.
     *
     * @param requestedRefundId 승인 대기 중 접수돼 이번 트랜잭션에서 REQUESTED가 된 환불. 없으면 null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void captureApproval(SaleSource source, Long requestedRefundId) {
        if (!enabled) return;
        if (items.findSaleByPaymentId(source.paymentId()).isPresent()) return;
        SettlementItem sale = SaleCalculation.calculate(rules, source);
        if (requestedRefundId != null) sale.holdForRefund(requestedRefundId);
        items.saveAndFlush(sale);
        if (sale.getStatus() == SettlementItemStatus.UNPOSTED) {
            log.error(
                    "정산 금액 계산 실패. 결제는 확정하고 보충 대상으로 남긴다. paymentId={}, reason={}",
                    source.paymentId(),
                    sale.getFailureReason());
        } else {
            ledger.post(LedgerJournals.sale(sale, source.approvedAt()));
        }
        observation.counter(
                "settlement.sale.captured", "status", sale.getStatus().name());
    }
}
