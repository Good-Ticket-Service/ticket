package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 전체 대조 SQL(settlement-design 8.1절). 각 항목은 위반한 행의 ID를 고르는 SELECT이고, 위반 수와 ID 일부만 남긴다.
 * 모든 항목을 REPEATABLE READ 한 트랜잭션(같은 스냅샷)에서 읽는다. 결제·환불·발행·지급은 각자 원천 상태와 분개를 한
 * 트랜잭션에 남기므로, 같은 스냅샷에서는 진행 중인 처리를 위반으로 잘못 세지 않는다.
 * Note: 행 CHECK·UNIQUE가 이미 막는 것(0원 줄, 한쪽만 금액 등)은 다시 보지 않고, 여러 행·여러 테이블에 걸친 식만 본다.
 */
@Repository
public class SettlementReconcileJdbcAdapter implements SettlementReconcileRepository {

    private static final int SAMPLE_SIZE = 10;

    /** 항목 이름 → 위반 행 ID를 고르는 SQL. 순서가 결과·로그 순서다. */
    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        // 원장: 분개마다 차변 = 대변, 줄 2개 이상(행 CHECK는 여러 줄 합계 균형을 대신하지 못한다)
        CHECKS.put("LEDGER_UNBALANCED", """
                SELECT t.id FROM p_ledger_transactions t LEFT JOIN p_ledger_entries e ON e.transaction_id = t.id
                GROUP BY t.id
                HAVING count(e.id) < 2 OR coalesce(sum(e.debit), 0) <> coalesce(sum(e.credit), 0)
                """);
        // 승인 결제(DONE·CANCELED)마다 SALE 1개. 없으면 판매자 몫이 정산서에서 조용히 빠진다(덜 지급)
        CHECKS.put("SALE_MISSING", """
                SELECT p.id FROM p_payments p
                WHERE p.status IN ('DONE', 'CANCELED') AND p.approved_at IS NOT NULL AND p.deleted_at IS NULL
                  AND (CAST(:since AS TIMESTAMP) IS NULL OR p.approved_at >= CAST(:since AS TIMESTAMP))
                  AND NOT EXISTS (SELECT 1 FROM p_settlement_items i WHERE i.payment_id = p.id AND i.kind = 'SALE')
                """);
        CHECKS.put("SALE_DUPLICATE", """
                SELECT payment_id FROM p_settlement_items WHERE kind = 'SALE' GROUP BY payment_id HAVING count(*) > 1
                """);
        // 정산 계산 실패. 결제는 성공했지만 정산에 안 들어간다
        CHECKS.put("SALE_UNPOSTED", """
                SELECT id FROM p_settlement_items WHERE kind = 'SALE' AND status = 'UNPOSTED'
                """);
        // 승인되지 않은 결제(FAILED·AUTO_VOID 등)의 SALE은 판매하지 않은 돈을 지급하게 만든다(더 지급)
        CHECKS.put("SALE_FOR_UNAPPROVED_PAYMENT", """
                SELECT i.id FROM p_settlement_items i JOIN p_payments p ON p.id = i.payment_id
                WHERE i.kind = 'SALE' AND (p.status NOT IN ('DONE', 'CANCELED') OR p.approved_at IS NULL)
                """);
        // 분개한 SALE마다 판매 분개 1개, 판매자 몫(SELLER_PAYABLE 대변) = SALE의 N
        CHECKS.put("SALE_JOURNAL", """
                SELECT i.id FROM p_settlement_items i
                LEFT JOIN (
                    SELECT t.source_id AS payment_id, count(DISTINCT t.id) AS journals,
                           coalesce(sum(CASE WHEN e.account = 'SELLER_PAYABLE' THEN e.credit - e.debit END), 0) AS payable
                    FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                    WHERE t.source_type = 'SALE' AND t.action = 'POST'
                    GROUP BY t.source_id) j ON j.payment_id = i.payment_id
                WHERE i.kind = 'SALE' AND i.status <> 'UNPOSTED'
                  AND (j.payment_id IS NULL OR j.journals <> 1 OR j.payable <> i.seller_amount)
                """);
        // 환불 진행 중인 결제의 SALE이 정산 대상(PENDING)으로 남으면 환불될 돈을 지급할 수 있다
        CHECKS.put("SALE_NOT_HELD_FOR_REFUND", """
                SELECT i.id FROM p_settlement_items i JOIN p_refunds r ON r.payment_id = i.payment_id
                WHERE i.kind = 'SALE' AND i.status = 'PENDING' AND r.deleted_at IS NULL
                  AND r.status IN ('WAITING', 'REQUESTED', 'FAILED')
                """);
        // 완료된 환불은 발행 전이면 SALE 취소, 발행 뒤면 차감 1개로 반영돼야 한다. 빠지면 환불된 돈을 지급한다
        CHECKS.put("REFUND_NOT_REFLECTED", """
                SELECT r.id FROM p_refunds r JOIN p_settlement_items i ON i.payment_id = r.payment_id AND i.kind = 'SALE'
                WHERE r.status = 'DONE' AND r.deleted_at IS NULL AND i.status <> 'UNPOSTED'
                  AND NOT (i.status = 'CANCELED'
                       OR EXISTS (SELECT 1 FROM p_settlement_items d WHERE d.kind = 'DEDUCTION' AND d.refund_id = r.id))
                """);
        // 발행 전 환불 취소는 판매 분개를 뒤집은 반대 분개가 있어야 한다
        CHECKS.put("CANCELED_WITHOUT_REVERSE", """
                SELECT i.id FROM p_settlement_items i
                WHERE i.kind = 'SALE' AND i.status = 'CANCELED'
                  AND (SELECT count(*) FROM p_ledger_transactions t
                       WHERE t.source_type = 'REFUND' AND t.action = 'REVERSE' AND t.payment_id = i.payment_id) <> 1
                """);
        // 발행 뒤 환불 차감마다 채권 분개 1개, 채권(SELLER_RECEIVABLE 차변) = 차감 원금
        CHECKS.put("DEDUCTION_JOURNAL", """
                SELECT d.id FROM p_settlement_items d
                LEFT JOIN (
                    SELECT t.source_id AS refund_id, count(DISTINCT t.id) AS journals,
                           coalesce(sum(CASE WHEN e.account = 'SELLER_RECEIVABLE' THEN e.debit - e.credit END), 0) AS receivable
                    FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                    WHERE t.source_type = 'REFUND' AND t.action = 'RECEIVABLE'
                    GROUP BY t.source_id) j ON j.refund_id = d.refund_id
                WHERE d.kind = 'DEDUCTION' AND (j.refund_id IS NULL OR j.journals <> 1 OR j.receivable <> d.principal)
                """);
        // 같은 SALE이 두 정산서에 들어가거나, 편입 표시와 명세행이 어긋나면 이중 지급·누락이 된다
        CHECKS.put("SALE_STATEMENT_LINK", """
                SELECT i.id FROM p_settlement_items i
                WHERE i.kind = 'SALE' AND (
                    (SELECT count(*) FROM p_settlement_statement_lines l WHERE l.item_id = i.id) > 1
                    OR (i.status = 'ON_STATEMENT' AND NOT EXISTS (SELECT 1 FROM p_settlement_statement_lines l
                        WHERE l.item_id = i.id AND l.statement_id = i.statement_id))
                    OR (i.status <> 'ON_STATEMENT'
                        AND EXISTS (SELECT 1 FROM p_settlement_statement_lines l WHERE l.item_id = i.id)))
                """);
        // 정산서 합계 = 명세 합, 지급액 = 판매 합계 − 상계액
        CHECKS.put("STATEMENT_TOTALS", """
                SELECT s.id FROM p_settlement_statements s
                WHERE s.sale_total <> coalesce((SELECT sum(amount) FROM p_settlement_statement_lines l
                                                WHERE l.statement_id = s.id AND l.kind = 'SALE'), 0)
                   OR s.offset_total <> coalesce((SELECT sum(amount) FROM p_settlement_statement_lines l
                                                  WHERE l.statement_id = s.id AND l.kind = 'DEDUCTION'), 0)
                   OR s.payable_amount <> s.sale_total - s.offset_total
                """);
        // 상계액이 있으면 상계 분개 1개가 같은 금액이고, 없으면 상계 분개가 없다
        CHECKS.put("STATEMENT_OFFSET_JOURNAL", """
                SELECT s.id FROM p_settlement_statements s
                LEFT JOIN (
                    SELECT t.source_id AS statement_id, count(DISTINCT t.id) AS journals,
                           coalesce(sum(CASE WHEN e.account = 'SELLER_PAYABLE' THEN e.debit - e.credit END), 0) AS payable
                    FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                    WHERE t.source_type = 'STATEMENT' AND t.action = 'OFFSET'
                    GROUP BY t.source_id) j ON j.statement_id = s.id
                WHERE (s.offset_total = 0 AND j.statement_id IS NOT NULL)
                   OR (s.offset_total > 0 AND (j.statement_id IS NULL OR j.journals <> 1 OR j.payable <> s.offset_total))
                """);
        // 차감 원금 = 모든 정산서에 배분한 합 + 잔액. 어긋나면 과다 상계(덜 지급)나 회수 누락이다
        CHECKS.put("DEDUCTION_ALLOCATION", """
                SELECT d.id FROM p_settlement_items d
                WHERE d.kind = 'DEDUCTION' AND d.principal <> d.remaining
                    + coalesce((SELECT sum(amount) FROM p_settlement_statement_lines l WHERE l.item_id = d.id), 0)
                """);
        // 지급할 정산서(ISSUED·PAID)는 같은 금액의 지급 1개, 지급액 0(NO_PAYOUT)은 지급 없음
        CHECKS.put("STATEMENT_PAYOUT", """
                SELECT s.id FROM p_settlement_statements s LEFT JOIN p_payouts p ON p.statement_id = s.id
                WHERE (s.status IN ('ISSUED', 'PAID') AND (p.id IS NULL OR p.amount <> s.payable_amount))
                   OR (s.status = 'NO_PAYOUT' AND p.id IS NOT NULL)
                """);
        // 지급 DONE ⇔ 정산서 PAID ⇔ 지급 분개 1개(SELLER_PAYABLE 차변 = PAYOUT_CLEARING 대변 = 지급액)
        CHECKS.put("PAYOUT_COMPLETION", """
                SELECT p.id FROM p_payouts p JOIN p_settlement_statements s ON s.id = p.statement_id
                LEFT JOIN (
                    SELECT t.source_id AS payout_id, count(DISTINCT t.id) AS journals,
                           coalesce(sum(CASE WHEN e.account = 'SELLER_PAYABLE' THEN e.debit - e.credit END), 0) AS payable,
                           coalesce(sum(CASE WHEN e.account = 'PAYOUT_CLEARING' THEN e.credit - e.debit END), 0) AS clearing
                    FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                    WHERE t.source_type = 'PAYOUT' AND t.action = 'DONE'
                    GROUP BY t.source_id) j ON j.payout_id = p.id
                WHERE (p.status = 'DONE') <> (s.status = 'PAID')
                   OR (p.status = 'DONE' AND (j.payout_id IS NULL OR j.journals <> 1
                       OR j.payable <> p.amount OR j.clearing <> p.amount))
                   OR (p.status <> 'DONE' AND j.payout_id IS NOT NULL)
                """);
        // 판매자별 미지급 잔액(SELLER_PAYABLE 대변 − 차변) = 아직 발행 안 한 SALE(PENDING·HELD)의 N + 미지급 정산서(ISSUED) 지급액
        CHECKS.put("SELLER_PAYABLE_BALANCE", """
                SELECT coalesce(l.seller_id, x.seller_id) FROM (
                    SELECT t.seller_id, sum(e.credit - e.debit) AS balance
                    FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                    WHERE e.account = 'SELLER_PAYABLE' GROUP BY t.seller_id) l
                FULL OUTER JOIN (
                    SELECT seller_id, sum(amount) AS expected FROM (
                        SELECT seller_id, seller_amount::BIGINT AS amount FROM p_settlement_items
                        WHERE kind = 'SALE' AND status IN ('PENDING', 'HELD')
                        UNION ALL
                        SELECT seller_id, payable_amount FROM p_settlement_statements WHERE status = 'ISSUED') u
                    GROUP BY seller_id) x ON x.seller_id = l.seller_id
                WHERE coalesce(l.balance, 0) <> coalesce(x.expected, 0)
                """);
        // 판매자별 미회수 잔액(SELLER_RECEIVABLE 차변 − 대변) = 상계 안 된 차감 잔액 합
        CHECKS.put("SELLER_RECEIVABLE_BALANCE", """
                SELECT coalesce(l.seller_id, x.seller_id) FROM (
                    SELECT t.seller_id, sum(e.debit - e.credit) AS balance
                    FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id = t.id
                    WHERE e.account = 'SELLER_RECEIVABLE' GROUP BY t.seller_id) l
                FULL OUTER JOIN (
                    SELECT seller_id, sum(remaining) AS expected FROM p_settlement_items
                    WHERE kind = 'DEDUCTION' AND status = 'OPEN' GROUP BY seller_id) x ON x.seller_id = l.seller_id
                WHERE coalesce(l.balance, 0) <> coalesce(x.expected, 0)
                """);
        // 지급 확정 누적액(PAYOUT_CLEARING) = DONE 지급액 합. 지급 분개 없이 바뀐 DONE이나 분개만 있는 지급을 전체로 잡는다
        CHECKS.put("PAYOUT_CLEARING_BALANCE", """
                SELECT 0 FROM (
                    SELECT coalesce((SELECT sum(e.credit - e.debit) FROM p_ledger_entries e
                                     WHERE e.account = 'PAYOUT_CLEARING'), 0) AS balance,
                           coalesce((SELECT sum(amount) FROM p_payouts WHERE status = 'DONE'), 0) AS expected) c
                WHERE c.balance <> c.expected
                """);
    }

    private final JdbcTemplate jdbc;

    public SettlementReconcileJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 대조 항목 이름(시험·지표가 같은 이름을 쓴다). */
    public static List<String> checkNames() {
        return List.copyOf(CHECKS.keySet());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public List<CheckResult> runChecks(LocalDateTime paymentsSince) {
        jdbc.execute("SET LOCAL statement_timeout = '60s'");
        Timestamp since = paymentsSince == null ? null : Timestamp.valueOf(paymentsSince);
        List<CheckResult> results = new ArrayList<>();
        for (Map.Entry<String, String> check : CHECKS.entrySet()) {
            String sql = "SELECT count(*), array_to_string((array_agg(v.id ORDER BY v.id))[1:" + SAMPLE_SIZE + "], ',')"
                    + " FROM (SELECT x.* FROM (" + check.getValue().replace(":since", "?") + ") AS x(id)) v";
            Object[] args = check.getValue().contains(":since") ? new Object[] {since, since} : new Object[] {};
            results.add(jdbc.queryForObject(
                    sql, (rs, i) -> new CheckResult(check.getKey(), rs.getLong(1), rs.getString(2)), args));
        }
        return results;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<DonePayout> findDonePayouts(LocalDateTime doneSince, int limit) {
        return jdbc.query(
                "SELECT id, idempotency_key, amount, account_ref, external_payout_id FROM p_payouts"
                        + " WHERE status = 'DONE' AND done_at >= ? ORDER BY id LIMIT ?",
                (rs, i) ->
                        new DonePayout(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)),
                Timestamp.valueOf(doneSince),
                limit);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long startRun(LocalDateTime now) {
        return jdbc.queryForObject(
                "INSERT INTO p_settlement_reconcile_runs (status, started_at) VALUES ('RUNNING', ?) RETURNING id",
                Long.class,
                Timestamp.valueOf(now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(long runId, Finished finished) {
        jdbc.update(
                "UPDATE p_settlement_reconcile_runs SET status = ?, finished_at = ?, violations = ?,"
                        + " provider_checked = ?, provider_unreachable = ?, error = ? WHERE id = ?",
                finished.status(),
                Timestamp.valueOf(finished.finishedAt()),
                finished.violations(),
                finished.providerChecked(),
                finished.providerUnreachable(),
                finished.error(),
                runId);
        for (CheckResult result : finished.results()) {
            jdbc.update(
                    "INSERT INTO p_settlement_reconcile_results (run_id, check_name, violations, sample) VALUES (?, ?, ?, ?)",
                    runId,
                    result.check(),
                    result.violations(),
                    result.sample());
        }
    }
}
