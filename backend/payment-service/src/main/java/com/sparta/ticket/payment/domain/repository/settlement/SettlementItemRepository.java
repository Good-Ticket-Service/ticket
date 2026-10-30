package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface SettlementItemRepository {

    SettlementItem saveAndFlush(SettlementItem item);

    /** 결제의 SALE. 결제마다 하나다(부분 UNIQUE). */
    Optional<SettlementItem> findSaleByPaymentId(Long paymentId);
}
