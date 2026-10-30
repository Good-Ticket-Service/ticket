package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.service.settlement.SettlementBackfillTransactions;
import com.sparta.ticket.payment.application.service.settlement.SettlementBackfillTransactions.Outcome;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 보충의 결제 쪽 트랜잭션. 결제가 자기 행을 잠그고 현재 결제·환불 상태를 스냅샷으로 넘긴다(settlement-design 4.4절).
 * 잠금 순서는 승인·환불 경로와 같다(payment → refund → SALE).
 */
@Component
@RequiredArgsConstructor
public class PaymentSettlementBackfillTransactions {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final SettlementBackfillTransactions settlement;

    @Transactional(readOnly = true)
    public List<Long> candidates(Long afterId, int limit) {
        return payments.findSettlementBackfillIds(afterId, limit);
    }

    /** 결제 1건을 보충한다. 잠근 뒤 다시 보면 대상이 아닐 수 있다(동시 보충·상태 변경). */
    @Transactional
    public Outcome backfill(Long paymentId) {
        Payment payment = payments.lockById(paymentId).orElse(null);
        if (payment == null
                || payment.getApprovedAt() == null
                || (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.CANCELED))
            return Outcome.SKIPPED;
        var refund = refunds.lockByPaymentId(paymentId)
                .filter(r -> r.getStatus() != RefundStatus.SKIPPED && r.getStatus() != RefundStatus.WAITING)
                .map(SettlementSnapshots::refund)
                .orElse(null);
        return settlement.backfill(SettlementSnapshots.sale(payment), refund);
    }
}
