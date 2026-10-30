package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface SettlementItemRepository {

    SettlementItem saveAndFlush(SettlementItem item);

    /** 결제의 SALE. 결제마다 하나다(부분 UNIQUE). */
    Optional<SettlementItem> findSaleByPaymentId(Long paymentId);

    /**
     * 결제의 SALE을 행 잠금으로 읽는다. 환불·보충이 정산서 발행과 같은 SALE에서 만날 때 먼저 잠근 쪽만 적용되게 한다.
     * 잠금 순서는 payment → refund → SALE이다(settlement-design 6절).
     */
    Optional<SettlementItem> lockSaleByPaymentId(Long paymentId);

    /** 환불의 DEDUCTION. 환불마다 하나다(부분 UNIQUE). */
    Optional<SettlementItem> findDeductionByRefundId(Long refundId);
}
