package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 정산서 한 장에 들어갈 항목을 잠근다: 이 회차의 정산 가능한 PENDING SALE과 판매자의 미회수 DEDUCTION을 합쳐 id 순으로.
     * 잠금을 기다린 사이 환불이 SALE을 HELD로 바꿨으면 잠근 뒤 다시 평가해 빠진다(settlement-design 6절).
     */
    List<SettlementItem> lockStatementItems(Long sellerId, Long scheduleId, LocalDateTime cutoff);

    /** cutoff까지 정산 가능해진 PENDING SALE이 있는 (판매자, 회차)를 (회차 시작 시각, 회차 ID, 판매자) 다음부터 읽는다. */
    List<StatementCandidate> findStatementCandidates(
            LocalDateTime cutoff, LocalDateTime afterStartAt, Long afterScheduleId, Long afterSellerId, int limit);
}
