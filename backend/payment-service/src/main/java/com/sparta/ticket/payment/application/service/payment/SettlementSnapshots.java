package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.model.settlement.RefundSnapshot;
import com.sparta.ticket.payment.domain.model.settlement.SaleSource;

/** 정산은 결제·환불 Entity를 보지 않고 결제가 만든 이 스냅샷만 받는다(settlement-design 4.1절). */
public final class SettlementSnapshots {

    private SettlementSnapshots() {}

    /** 승인된 결제의 정산 입력. 예매수수료는 미적용 결정이라 0이다. */
    public static SaleSource sale(Payment payment) {
        return new SaleSource(
                payment.getId(),
                payment.getSellerId(),
                payment.getPerformanceId(),
                payment.getScheduleId(),
                payment.getScheduleStartAt(),
                payment.getApprovedAt(),
                payment.getTotalPrice(),
                payment.getDiscountAmount(),
                0,
                payment.getAmount());
    }

    /** 환불의 현재 상태. DONE만 완료이고, 접수·실패·검증 대기는 아직 진행 중으로 본다. */
    public static RefundSnapshot refund(Refund refund) {
        boolean completed = refund.getStatus() == RefundStatus.DONE;
        return new RefundSnapshot(refund.getId(), completed, completed ? refund.getRefundedAt() : null);
    }
}
