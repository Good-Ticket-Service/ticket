package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementItemJpaRepository extends JpaRepository<SettlementItem, Long>, SettlementItemRepository {

    @Override
    @Query("SELECT i FROM SettlementItem i WHERE i.paymentId = :paymentId"
            + " AND i.kind = com.sparta.ticket.payment.domain.model.settlement.SettlementItemKind.SALE")
    Optional<SettlementItem> findSaleByPaymentId(@Param("paymentId") Long paymentId);
}
