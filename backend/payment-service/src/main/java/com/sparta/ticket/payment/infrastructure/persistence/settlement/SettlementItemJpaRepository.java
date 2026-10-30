package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.domain.repository.settlement.StatementCandidate;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
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

    @Override
    @Query(
            value = "SELECT * FROM p_settlement_items WHERE seller_id = :sellerId AND deleted_at IS NULL AND ("
                    + "(kind = 'SALE' AND status = 'PENDING' AND schedule_id = :scheduleId AND eligible_at <= :cutoff)"
                    + " OR (kind = 'DEDUCTION' AND status = 'OPEN')) ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<SettlementItem> lockStatementItems(
            @Param("sellerId") Long sellerId,
            @Param("scheduleId") Long scheduleId,
            @Param("cutoff") LocalDateTime cutoff);

    @Override
    @Query(
            value =
                    "SELECT seller_id AS \"sellerId\", schedule_id AS \"scheduleId\", MIN(schedule_start_at) AS \"scheduleStartAt\""
                            + " FROM p_settlement_items WHERE kind = 'SALE' AND status = 'PENDING' AND eligible_at <= :cutoff"
                            + " AND deleted_at IS NULL GROUP BY seller_id, schedule_id"
                            + " HAVING (MIN(schedule_start_at), schedule_id, seller_id) > (:afterStartAt, :afterScheduleId, :afterSellerId)"
                            + " ORDER BY 3, 2, 1 LIMIT :limit",
            nativeQuery = true)
    List<StatementCandidate> findStatementCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("afterStartAt") LocalDateTime afterStartAt,
            @Param("afterScheduleId") Long afterScheduleId,
            @Param("afterSellerId") Long afterSellerId,
            @Param("limit") int limit);
}
