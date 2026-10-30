package com.sparta.ticket.payment.domain.repository.settlement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 판매자 화면용 정산 읽기. 항상 판매자 ID로 거르므로 다른 판매자의 정산서는 보이지 않는다. */
public interface SellerStatementQueries {

    /** 발행 시각 [from, to) 범위, 발행 시각·ID 내림차순. status가 null이면 전체. */
    List<Summary> findStatements(
            Long sellerId, LocalDateTime from, LocalDateTime to, String status, int offset, int limit);

    long countStatements(Long sellerId, LocalDateTime from, LocalDateTime to, String status);

    Optional<Summary> findStatement(Long sellerId, Long statementId);

    List<Line> findLines(Long statementId);

    Receivable findReceivable(Long sellerId);

    /** 지급이 없는 정산서(NO_PAYOUT)는 payoutStatus가 null이다. 회차 시작 시각은 편입한 SALE에서 가져온다. */
    record Summary(
            Long statementId,
            Long scheduleId,
            LocalDateTime scheduleStartAt,
            int sequence,
            long saleTotal,
            long offsetTotal,
            long payableAmount,
            String status,
            LocalDateTime issuedAt,
            String payoutStatus) {}

    /** SALE은 결제 금액 내역과 판매자 몫 전액, DEDUCTION은 이 정산서에서 상계한 몫만 amount에 담는다. */
    record Line(
            Long itemId,
            String kind,
            Long paymentId,
            Integer grossAmount,
            Integer commission,
            Integer commissionVat,
            Integer sellerAmount,
            long amount) {}

    /** 아직 상계하지 못한 차감 잔액 합과 가장 오래된 차감의 생성 시각. 없으면 0과 null이다. */
    record Receivable(long receivable, LocalDateTime oldestAt) {}
}
