package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.OffsetAllocation;
import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemKind;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatement;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatementLine;
import com.sparta.ticket.payment.domain.model.settlement.StatementStatus;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementStatementRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자·회차 하나의 정산서 발행(Task 20-5, settlement-design 5절). 발행 하나가 트랜잭션 하나다.
 * 재실행 안전성은 잡 실행 기록이 아니라 DB가 보장한다. 편입한 SALE은 ON_STATEMENT라 다시 뽑히지 않고,
 * 같은 SALE은 명세행 부분 UNIQUE로 한 번만 들어간다. 그래서 스케줄러든 Spring Batch든 이 메서드를 그대로 부를 수 있다.
 * Note: Spring Batch로 바꿀 때 청크 트랜잭션이 여러 발행을 한 트랜잭션으로 묶지 않게 한다(청크 1, REQUIRES_NEW, Tasklet 반복 중 하나).
 */
@Component
@RequiredArgsConstructor
public class SettlementStatementTransactions {

    public enum Outcome {
        /** 정산서와 지급(READY)을 만들었다. */
        ISSUED,
        /** 상계로 지급액이 0이라 정산서만 만들었다. */
        NO_PAYOUT,
        /** 잠근 뒤 보니 편입할 SALE이 없다(환불 보류 등). 정산서를 만들지 않는다. */
        NONE,
        /** 다른 발행이 같은 판매자를 처리 중이다. 완료가 아니므로 다음 실행이 다시 찾는다. */
        SKIPPED_LOCKED
    }

    private final SettlementItemRepository items;
    private final SettlementStatementRepository statements;
    private final LedgerTransactions ledger;
    private final SettlementRules rules;
    private final CommittedObservation observation;
    private final Clock clock;

    /** 환불·보충이 SALE을 오래 잡고 있어도 발행이 판매자 잠금을 쥔 채 무기한 기다리지 않게 한다. */
    @Value("${payment.settlement.statement.lock-timeout-ms:5000}")
    private long lockTimeoutMillis;

    /**
     * 잠금 순서: 판매자 기준 행 → 항목(id 순). 결제·환불 경로는 판매자 행을 잡지 않으므로 두 묶음이 공유하는 잠금은 SALE 행뿐이다.
     * Note: 항목은 이 트랜잭션에서 처음 읽는다. 바깥 트랜잭션에 합류해 같은 항목이 이미 영속성 컨텍스트에 있으면
     * native FOR UPDATE가 잠금만 잡고 낡은 상태를 돌려줄 수 있으므로, 발행은 항상 자기 트랜잭션에서 실행한다.
     *
     * @param cutoff 발행 잡 시작 시각. 이 시각까지 정산 가능해진 SALE만 담는다
     */
    @Transactional
    public Outcome issue(Long sellerId, Long scheduleId, LocalDateTime cutoff) {
        statements.limitLockWait(lockTimeoutMillis);
        statements.ensureSellerAccount(sellerId);
        if (statements.lockSellerAccountSkipLocked(sellerId).isEmpty()) return Outcome.SKIPPED_LOCKED;
        List<SettlementItem> locked = items.lockStatementItems(sellerId, scheduleId, cutoff);
        List<SettlementItem> sales = locked.stream()
                .filter(item -> item.getKind() == SettlementItemKind.SALE)
                .toList();
        if (sales.isEmpty()) return Outcome.NONE;
        List<SettlementItem> deductions = locked.stream()
                .filter(item -> item.getKind() == SettlementItemKind.DEDUCTION)
                .toList();

        long saleTotal = 0;
        for (SettlementItem sale : sales)
            saleTotal = Math.addExact(saleTotal, sale.amounts().sellerAmount());
        List<Long> shares = deductions.isEmpty()
                ? List.of()
                : OffsetAllocation.allocate(
                        saleTotal,
                        deductions.stream().map(SettlementItem::getRemaining).toList());
        long offsetTotal = shares.stream().mapToLong(Long::longValue).sum();

        LocalDateTime now = LocalDateTime.now(clock);
        SettlementStatement statement = statements.saveAndFlush(SettlementStatement.issue(
                sellerId,
                scheduleId,
                statements.nextSequence(sellerId, scheduleId),
                cutoff,
                saleTotal,
                offsetTotal,
                now));
        List<SettlementStatementLine> lines = new ArrayList<>();
        for (SettlementItem sale : sales) {
            lines.add(SettlementStatementLine.of(statement, sale, sale.amounts().sellerAmount()));
            sale.putOnStatement(statement.getId());
        }
        for (int i = 0; i < deductions.size(); i++) {
            long share = shares.get(i);
            if (share == 0) continue;
            lines.add(SettlementStatementLine.of(statement, deductions.get(i), share));
            deductions.get(i).offsetBy(share);
        }
        statements.saveLines(lines);
        if (offsetTotal > 0) ledger.post(LedgerJournals.offset(statement.getId(), sellerId, offsetTotal, now));
        if (statement.getStatus() == StatementStatus.NO_PAYOUT) {
            observation.counter("settlement.statement.issued", "result", Outcome.NO_PAYOUT.name());
            return Outcome.NO_PAYOUT;
        }
        statements.savePayout(Payout.ready(statement, rules.nextPayoutAt(cutoff).orElse(null)));
        observation.counter("settlement.statement.issued", "result", Outcome.ISSUED.name());
        return Outcome.ISSUED;
    }
}
