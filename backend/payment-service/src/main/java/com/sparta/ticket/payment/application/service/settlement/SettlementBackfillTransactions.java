package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.RefundSnapshot;
import com.sparta.ticket.payment.domain.model.settlement.SaleSource;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포착이 빠진 결제와 계산에 실패한(UNPOSTED) SALE을 메운다(Task 20-4, settlement-design 4.4절).
 * 판매 분개를 반영한 뒤 현재 환불 상태(접수·완료)까지 같은 트랜잭션에서 맞춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBackfillTransactions {

    public enum Outcome {
        /** 이미 분개한 SALE이 있어 할 일이 없다. */
        SKIPPED,
        /** 판매 분개를 반영했다(환불 상태까지 맞춤). */
        POSTED,
        /** 다시 계산해도 실패해 UNPOSTED로 남았다. */
        UNPOSTED
    }

    private final SettlementRules rules;
    private final SettlementItemRepository items;
    private final LedgerTransactions ledger;
    private final SettlementRefundTransactions refunds;
    private final CommittedObservation observation;

    /**
     * Note: 호출자가 payment → refund 순서로 잠근 트랜잭션에서 부른다. 여기서 SALE을 잠가 같은 결제의 포착·환불·다른 보충과 직렬화한다.
     *
     * @param refund 결제의 환불 현재 상태. 없으면 null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome backfill(SaleSource source, RefundSnapshot refund) {
        Optional<SettlementItem> existing = items.lockSaleByPaymentId(source.paymentId());
        if (existing.isPresent() && existing.get().getStatus() != SettlementItemStatus.UNPOSTED) return Outcome.SKIPPED;
        SettlementItem calculated = SaleCalculation.calculate(rules, source);
        SettlementItem sale = existing.orElse(calculated);
        if (existing.isPresent()) sale.adoptCalculation(calculated);
        if (sale.getStatus() == SettlementItemStatus.UNPOSTED) {
            if (refund != null) sale.holdForRefund(refund.refundId());
            items.saveAndFlush(sale);
            log.error(
                    "정산 보충 계산 실패. 다음 순회에 다시 시도한다. paymentId={}, reason={}",
                    source.paymentId(),
                    sale.getFailureReason());
            observation.counter("settlement.backfill", "result", Outcome.UNPOSTED.name());
            return Outcome.UNPOSTED;
        }
        items.saveAndFlush(sale);
        ledger.post(LedgerJournals.sale(sale, source.approvedAt()));
        if (refund != null && refund.completed())
            refunds.refundCompleted(source.paymentId(), refund.refundId(), refund.completedAt());
        else if (refund != null) refunds.refundAccepted(source.paymentId(), refund.refundId());
        observation.counter("settlement.backfill", "result", Outcome.POSTED.name());
        return Outcome.POSTED;
    }
}
