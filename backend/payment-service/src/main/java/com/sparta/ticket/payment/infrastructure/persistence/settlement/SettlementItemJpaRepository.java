package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementItemJpaRepository extends JpaRepository<SettlementItem, Long>, SettlementItemRepository {

    @Override
    @Query("SELECT i FROM SettlementItem i WHERE i.paymentId = :paymentId"
            + " AND i.kind = com.sparta.ticket.payment.domain.model.settlement.SettlementItemKind.SALE")
    Optional<SettlementItem> findSaleByPaymentId(@Param("paymentId") Long paymentId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SettlementItem i WHERE i.paymentId = :paymentId"
            + " AND i.kind = com.sparta.ticket.payment.domain.model.settlement.SettlementItemKind.SALE")
    Optional<SettlementItem> lockSaleByPaymentId(@Param("paymentId") Long paymentId);

    @Override
    @Query("SELECT i FROM SettlementItem i WHERE i.refundId = :refundId"
            + " AND i.kind = com.sparta.ticket.payment.domain.model.settlement.SettlementItemKind.DEDUCTION")
    Optional<SettlementItem> findDeductionByRefundId(@Param("refundId") Long refundId);
}
