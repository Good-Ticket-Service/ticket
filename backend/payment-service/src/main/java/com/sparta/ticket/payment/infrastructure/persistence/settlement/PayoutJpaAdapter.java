package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatement;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 지급 실행권·결과 반영·게이트. 지급과 정산서를 함께 잠그므로 EntityManager로 묶는다. */
@Repository
public class PayoutJpaAdapter implements PayoutRepository {

    /**
     * 게이트 SQL. 정산서·지급·명세·항목·원장을 한 문장으로 읽어 READ COMMITTED에서도 같은 스냅샷으로 판단한다.
     * 다른 정산서에 편입된 차감이 뒤에 생겨도 원금 = 전체 명세 배분 합 + 잔액은 유지되므로 정상 유입을 오류로 보지 않는다.
     */
    private static final String GATE_SQL = """
            WITH s AS (
                SELECT id, seller_id, status, sale_total, offset_total, payable_amount
                FROM p_settlement_statements WHERE id = :statementId),
            p AS (
                SELECT amount, status, seller_id FROM p_payouts WHERE id = :payoutId AND statement_id = :statementId),
            lines AS (
                SELECT l.item_id, l.kind AS line_kind, l.amount, i.kind, i.status, i.statement_id, i.seller_id,
                       i.seller_amount, i.payment_id, i.principal, i.remaining
                FROM p_settlement_statement_lines l JOIN p_settlement_items i ON i.id = l.item_id
                WHERE l.statement_id = :statementId),
            sale_journal AS (
                SELECT t.source_id AS payment_id, t.seller_id, SUM(e.debit) AS debit, SUM(e.credit) AS credit,
                       SUM(CASE WHEN e.account = 'SELLER_PAYABLE' THEN e.credit - e.debit ELSE 0 END) AS payable
                FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                WHERE t.source_type = 'SALE' AND t.action = 'POST'
                  AND t.source_id IN (SELECT payment_id FROM lines WHERE line_kind = 'SALE')
                GROUP BY t.source_id, t.seller_id),
            offset_journal AS (
                SELECT COUNT(DISTINCT t.id) AS journals, COALESCE(SUM(e.debit), 0) AS debit,
                       COALESCE(SUM(e.credit), 0) AS credit,
                       COALESCE(SUM(CASE WHEN e.account = 'SELLER_PAYABLE' THEN e.debit - e.credit ELSE 0 END), 0) AS payable,
                       COALESCE(SUM(CASE WHEN e.account = 'SELLER_RECEIVABLE' THEN e.credit - e.debit ELSE 0 END), 0) AS receivable
                FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                WHERE t.source_type = 'STATEMENT' AND t.action = 'OFFSET' AND t.source_id = :statementId)
            SELECT CASE
                WHEN NOT EXISTS (SELECT 1 FROM s) OR NOT EXISTS (SELECT 1 FROM p) THEN 'STATEMENT_MISSING'
                WHEN (SELECT status FROM s) <> 'ISSUED' OR (SELECT status FROM p) <> 'READY' THEN 'STATUS'
                WHEN (SELECT seller_id FROM p) <> (SELECT seller_id FROM s) THEN 'PAYOUT_SELLER'
                WHEN (SELECT payable_amount FROM s) <> (SELECT sale_total - offset_total FROM s) THEN 'PAYABLE_FORMULA'
                WHEN (SELECT amount FROM p) <> (SELECT payable_amount FROM s) THEN 'PAYOUT_AMOUNT'
                WHEN (SELECT COALESCE(SUM(amount), 0) FROM lines WHERE line_kind = 'SALE')
                    <> (SELECT sale_total FROM s) THEN 'SALE_LINES_TOTAL'
                WHEN (SELECT COALESCE(SUM(amount), 0) FROM lines WHERE line_kind = 'DEDUCTION')
                    <> (SELECT offset_total FROM s) THEN 'DEDUCTION_LINES_TOTAL'
                WHEN EXISTS (SELECT 1 FROM lines WHERE kind <> line_kind
                    OR seller_id <> (SELECT seller_id FROM s)) THEN 'LINE_SOURCE'
                WHEN EXISTS (SELECT 1 FROM lines WHERE line_kind = 'SALE' AND (status <> 'ON_STATEMENT'
                    OR statement_id IS DISTINCT FROM :statementId
                    OR seller_amount IS NULL OR seller_amount <> amount)) THEN 'SALE_ITEM'
                WHEN EXISTS (SELECT 1 FROM lines l LEFT JOIN sale_journal j ON j.payment_id = l.payment_id
                    WHERE l.line_kind = 'SALE' AND (j.payment_id IS NULL OR j.seller_id <> l.seller_id
                        OR j.debit <> j.credit OR j.payable <> l.amount)) THEN 'SALE_JOURNAL'
                WHEN EXISTS (SELECT 1 FROM lines l WHERE l.line_kind = 'DEDUCTION' AND (l.principal IS NULL
                    OR l.principal <> l.remaining + (SELECT SUM(x.amount) FROM p_settlement_statement_lines x
                        WHERE x.item_id = l.item_id))) THEN 'DEDUCTION_ALLOCATION'
                WHEN (SELECT offset_total FROM s) = 0 AND (SELECT journals FROM offset_journal) <> 0 THEN 'OFFSET_JOURNAL'
                WHEN (SELECT offset_total FROM s) > 0 AND NOT (SELECT journals = 1 AND debit = credit
                    AND payable = (SELECT offset_total FROM s) AND receivable = (SELECT offset_total FROM s)
                    FROM offset_journal) THEN 'OFFSET_JOURNAL'
            END
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> findDueReadyIds(LocalDateTime now, int limit) {
        List<Number> ids = entityManager
                .createNativeQuery("SELECT id FROM p_payouts WHERE status = 'READY' AND next_attempt_at <= :now"
                        + " ORDER BY next_attempt_at, id")
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Payout> lockDueReady(Long payoutId, LocalDateTime now) {
        List<Payout> rows = entityManager
                .createNativeQuery(
                        "SELECT * FROM p_payouts WHERE id = :id AND status = 'READY' AND next_attempt_at <= :now"
                                + " FOR UPDATE SKIP LOCKED",
                        Payout.class)
                .setParameter("id", payoutId)
                .setParameter("now", now)
                .getResultList();
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Payout> find(Long payoutId) {
        return Optional.ofNullable(entityManager.find(Payout.class, payoutId));
    }

    @Override
    public Optional<Payout> lock(Long payoutId) {
        return Optional.ofNullable(entityManager.find(Payout.class, payoutId, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public Optional<SettlementStatement> lockStatement(Long statementId) {
        return Optional.ofNullable(
                entityManager.find(SettlementStatement.class, statementId, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public Optional<String> gateViolation(Long payoutId, Long statementId) {
        Object violation = entityManager
                .createNativeQuery(GATE_SQL)
                .setParameter("payoutId", payoutId)
                .setParameter("statementId", statementId)
                .getSingleResult();
        return Optional.ofNullable((String) violation);
    }

    @Override
    public void limitLockWait(long timeoutMillis) {
        // SET은 바인딩 변수를 받지 않는다. 숫자만 들어가므로 문자열로 만들어도 안전하다
        entityManager
                .createNativeQuery("SET LOCAL lock_timeout = '" + timeoutMillis + "ms'")
                .executeUpdate();
    }
}
