package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.repository.settlement.LedgerTransactionRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 접수·완료를 정산 항목과 원장에 반영한다(Task 20-4, settlement-design 4.3절).
 * 환불 트랜잭션 안에서만 부르며, 포착 스위치와 관계없이 항상 동작한다. 이미 기록된 SALE의 환불을 놓치면 취소된 판매가 지급되기 때문이다.
 * 호출자는 payment → refund 순서로 잠근 뒤 부르고, 여기서 SALE을 잠근다(payment → refund → SALE).
 */
@Component
@RequiredArgsConstructor
public class SettlementRefundTransactions {

    private final SettlementItemRepository items;
    private final LedgerTransactionRepository ledgerEntries;
    private final LedgerTransactions ledger;
    private final CommittedObservation observation;

    /** 환불 접수. 정산서에 들어가기 전 SALE은 보류하고, 계산 실패 SALE은 환불 ID만 남긴다. 원장은 바꾸지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundAccepted(Long paymentId, Long refundId) {
        items.lockSaleByPaymentId(paymentId).ifPresent(sale -> {
            SettlementItemStatus status = sale.getStatus();
            if (status == SettlementItemStatus.ON_STATEMENT || status == SettlementItemStatus.CANCELED) return;
            sale.holdForRefund(refundId);
            items.saveAndFlush(sale);
        });
    }

    /**
     * 환불 완료. 정산서 전이면 판매 분개를 그대로 뒤집고 SALE을 취소한다. 정산서 뒤면 발행한 정산서는 고정이므로
     * 판매자 몫 N 전액을 돌려받을 채권(DEDUCTION)으로 잡는다. SALE이 없으면(포착 OFF 기간, 실패 결제의 자동 취소) 아무것도 하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundCompleted(Long paymentId, Long refundId, LocalDateTime completedAt) {
        items.lockSaleByPaymentId(paymentId).ifPresent(sale -> {
            switch (sale.getStatus()) {
                case PENDING, HELD -> cancelBeforeStatement(sale, refundId, completedAt);
                case ON_STATEMENT -> deductAfterStatement(sale, refundId, completedAt);
                case UNPOSTED -> {
                    // 금액이 없어 분개할 수 없다. 보충이 판매·반대 분개를 함께 반영한다.
                    sale.holdForRefund(refundId);
                    items.saveAndFlush(sale);
                }
                default -> {}
            }
        });
    }

    private void cancelBeforeStatement(SettlementItem sale, Long refundId, LocalDateTime completedAt) {
        LedgerTransaction original = ledgerEntries
                .findByKey(LedgerSourceType.SALE, sale.getPaymentId(), LedgerAction.POST)
                .orElseThrow(() -> new IllegalStateException("분개한 SALE에 판매 분개가 없다: " + sale.getPaymentId()));
        ledger.post(LedgerJournals.reverse(original, refundId, completedAt));
        sale.cancelByRefund(refundId);
        items.saveAndFlush(sale);
        observation.counter("settlement.refund.reflected", "result", "CANCELED");
    }

    private void deductAfterStatement(SettlementItem sale, Long refundId, LocalDateTime completedAt) {
        if (items.findDeductionByRefundId(refundId).isPresent()) return;
        ledger.post(LedgerJournals.receivable(sale, refundId, completedAt));
        items.saveAndFlush(SettlementItem.deduction(sale, refundId));
        observation.counter("settlement.refund.reflected", "result", "DEDUCTION");
    }
}
