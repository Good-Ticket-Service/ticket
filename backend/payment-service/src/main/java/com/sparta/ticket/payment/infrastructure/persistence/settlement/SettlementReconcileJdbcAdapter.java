package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
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
        // Note: 반대 분개를 결제별로 한 번 센 뒤 붙인다(#310). 취소 SALE마다 원장을 payment_id로 다시 세면 그 열에 인덱스가 없어
        // REFUND 분개 범위 전체를 매번 훑는다(취소 수 × 환불 분개 수). REFUND 분개는 부분 인덱스로 그 범위만 읽는다
        CHECKS.put("CANCELED_WITHOUT_REVERSE", """
                SELECT i.id FROM p_settlement_items i
                LEFT JOIN (
                    SELECT t.payment_id, count(*) AS reverses FROM p_ledger_transactions t
                    WHERE t.source_type = 'REFUND' AND t.action = 'REVERSE'
                    GROUP BY t.payment_id) r ON r.payment_id = i.payment_id
                WHERE i.kind = 'SALE' AND i.status = 'CANCELED' AND coalesce(r.reverses, 0) <> 1
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
        // Note: 명세를 항목별로 한 번 모은 뒤 붙인다(#310). 항목마다 명세를 item_id로 다시 세면 (statement_id, item_id) 인덱스를
        // 매번 전체 훑어 이력 규모에서 끝나지 않는다. 명세 종류(l.kind)로 거르지 않는다. 거르면 종류가 잘못된 연결을 놓친다.
        // 명세가 2개 이상이면 그것만으로 위반이라, 1개일 때의 정산서 비교는 min(statement_id)로 충분하다
        CHECKS.put("SALE_STATEMENT_LINK", """
                SELECT i.id FROM p_settlement_items i
                LEFT JOIN (
                    SELECT l.item_id, count(*) AS lines, min(l.statement_id) AS statement_id
                    FROM p_settlement_statement_lines l GROUP BY l.item_id) l ON l.item_id = i.id
                WHERE i.kind = 'SALE' AND (
                    coalesce(l.lines, 0) > 1
                    OR (i.status = 'ON_STATEMENT' AND (l.lines IS NULL OR l.statement_id IS DISTINCT FROM i.statement_id))
                    OR (i.status <> 'ON_STATEMENT' AND l.lines IS NOT NULL))
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
        // Note: 차감에 걸린 명세를 한 번 합산한 뒤 붙인다(#310). 차감마다 명세를 item_id로 다시 합산하면 SALE 부분 인덱스를 못 써
        // 명세 인덱스 전체를 매번 훑는다(차감 수 × 명세 수). 명세 종류로 거르지 않는다
        CHECKS.put("DEDUCTION_ALLOCATION", """
                SELECT d.id FROM p_settlement_items d
                LEFT JOIN (
                    SELECT l.item_id, sum(l.amount) AS allocated
                    FROM p_settlement_statement_lines l
                    JOIN p_settlement_items x ON x.id = l.item_id AND x.kind = 'DEDUCTION'
                    GROUP BY l.item_id) a ON a.item_id = d.id
                WHERE d.kind = 'DEDUCTION' AND d.principal <> d.remaining + coalesce(a.allocated, 0)
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
    private final Duration statementTimeout;

    /** @param statementTimeout 검사 SQL 하나의 제한 시간. 넘으면 그 검사만 미완료로 남긴다 */
    public SettlementReconcileJdbcAdapter(
            JdbcTemplate jdbc,
            @Value("${payment.settlement.reconcile.statement-timeout:60s}") Duration statementTimeout) {
        this.jdbc = jdbc;
        this.statementTimeout = statementTimeout;
    }

    /** 대조 항목 이름(시험·지표가 같은 이름을 쓴다). */
    public static List<String> checkNames() {
        return List.copyOf(CHECKS.keySet());
    }

    /**
     * 모든 검사를 한 스냅샷에서 순서대로 실행한다. 검사마다 SAVEPOINT를 두어, 한 검사가 제한 시간을 넘거나 실패하면 그 검사만
     * 되돌리고 미완료로 남긴 채 다음 검사를 계속한다(#310). 되돌려도 REPEATABLE READ 스냅샷은 트랜잭션 끝까지 그대로라
     * 뒤 검사도 같은 시점을 본다. 연결이 끊기는 등 트랜잭션 자체가 실패하면 예외로 끝난다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public List<CheckResult> runChecks(LocalDateTime paymentsSince) {
        jdbc.execute("SET LOCAL statement_timeout = '" + statementTimeout.toMillis() + "ms'");
        Timestamp since = paymentsSince == null ? null : Timestamp.valueOf(paymentsSince);
        List<CheckResult> results = new ArrayList<>();
        for (Map.Entry<String, String> check : CHECKS.entrySet()) {
            String sql = "SELECT count(*), array_to_string((array_agg(v.id ORDER BY v.id))[1:" + SAMPLE_SIZE + "], ',')"
                    + " FROM (SELECT x.* FROM (" + check.getValue().replace(":since", "?") + ") AS x(id)) v";
            Object[] args = check.getValue().contains(":since") ? new Object[] {since, since} : new Object[] {};
            long started = System.nanoTime();
            jdbc.execute("SAVEPOINT reconcile_check");
            try {
                CheckResult result = jdbc.queryForObject(
                        sql,
                        (rs, i) -> CheckResult.completed(
                                check.getKey(), rs.getLong(1), rs.getString(2), elapsedMillis(started)),
                        args);
                jdbc.execute("RELEASE SAVEPOINT reconcile_check");
                results.add(result);
            } catch (DataAccessException e) {
                // 실패한 문장 뒤 트랜잭션은 SAVEPOINT로 되돌리기 전까지 다른 문장을 받지 않는다
                jdbc.execute("ROLLBACK TO SAVEPOINT reconcile_check");
                results.add(CheckResult.incomplete(
                        check.getKey(), elapsedMillis(started), e.getClass().getSimpleName()));
            }
        }
        return results;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
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
                    "INSERT INTO p_settlement_reconcile_results"
                            + " (run_id, check_name, completed, violations, sample, duration_ms, error)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    runId,
                    result.check(),
                    result.completed(),
                    result.violations(),
                    result.sample(),
                    result.durationMs(),
                    result.error());
        }
    }
}
