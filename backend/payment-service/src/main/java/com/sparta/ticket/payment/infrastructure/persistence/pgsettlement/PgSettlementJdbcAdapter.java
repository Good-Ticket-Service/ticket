package com.sparta.ticket.payment.infrastructure.persistence.pgsettlement;

import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementStore;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PgSettlementJdbcAdapter implements PgSettlementStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private static final String LINES = """
            SELECT l.*,d.settlement_date,d.status AS day_status
            FROM p_pg_settlement_lines l JOIN p_pg_settlement_days d ON d.id=l.day_id
            """;

    @Override
    public long receive(PgSettlement.Page page) {
        var t = page.totals();
        jdbc.update(
                """
                INSERT INTO p_pg_settlement_days(provider,settlement_date,snapshot_id,line_count,amount,fee,carry_in,payout,carry_out)
                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(provider,settlement_date) DO NOTHING
                """,
                page.provider(),
                page.settlementDate(),
                page.snapshotId(),
                t.count(),
                t.amount(),
                t.fee(),
                t.carryIn(),
                t.payout(),
                t.carryOut());
        var header = jdbc.queryForMap(
                "SELECT * FROM p_pg_settlement_days WHERE provider=? AND settlement_date=? FOR UPDATE",
                page.provider(),
                page.settlementDate());
        if (!page.snapshotId().equals(header.get("snapshot_id"))
                || ((Number) header.get("line_count")).longValue() != t.count()
                || ((Number) header.get("amount")).longValue() != t.amount()
                || ((Number) header.get("fee")).longValue() != t.fee()
                || ((Number) header.get("carry_in")).longValue() != t.carryIn()
                || ((Number) header.get("payout")).longValue() != t.payout()
                || ((Number) header.get("carry_out")).longValue() != t.carryOut())
            throw new IllegalStateException("확정 명세가 변경됐다. 이어 읽기를 중단한다");
        if (!"RECEIVING".equals(header.get("status"))) throw new IllegalStateException("수집이 끝난 명세에 쓸 수 없다");
        long dayId = ((Number) header.get("id")).longValue();
        if (page.lines().isEmpty()) return dayId;
        var values = String.join(",", java.util.Collections.nCopies(page.lines().size(), "(?,?,?,?,?,?,?,?,?)"));
        var arguments = new java.util.ArrayList<Object>();
        for (var line : page.lines())
            java.util.Collections.addAll(
                    arguments,
                    dayId,
                    page.provider(),
                    line.lineKey(),
                    line.paymentKey(),
                    line.orderId(),
                    line.type(),
                    line.amount(),
                    line.fee(),
                    line.occurredAt());
        int count = jdbc.update("""
                INSERT INTO p_pg_settlement_lines(day_id,provider,line_key,payment_key,order_id,type,amount,fee,occurred_at)
                VALUES
                """ + values + """
                 ON CONFLICT(provider,line_key) DO UPDATE SET updated_at=p_pg_settlement_lines.updated_at
                WHERE p_pg_settlement_lines.day_id=excluded.day_id
                  AND p_pg_settlement_lines.payment_key=excluded.payment_key
                  AND p_pg_settlement_lines.order_id=excluded.order_id AND p_pg_settlement_lines.type=excluded.type
                  AND p_pg_settlement_lines.amount=excluded.amount AND p_pg_settlement_lines.fee=excluded.fee
                  AND p_pg_settlement_lines.occurred_at=excluded.occurred_at
                """, arguments.toArray());
        if (count != page.lines().size()) throw new IllegalStateException("같은 명세 사건 키에 다른 내용이 들어왔다");
        return dayId;
    }

    @Override
    public void verifyReceived(String provider, LocalDate date) {
        var sums = jdbc.queryForMap("""
                SELECT count(l.id) AS n,coalesce(sum(l.amount),0) AS amount,coalesce(sum(l.fee),0) AS fee
                FROM p_pg_settlement_days d LEFT JOIN p_pg_settlement_lines l ON l.day_id=d.id
                WHERE d.provider=? AND d.settlement_date=?
                """, provider, date);
        Day day = lockDay(provider, date);
        if (((Number) sums.get("n")).longValue() != day.totals().count()
                || ((Number) sums.get("amount")).longValue() != day.totals().amount()
                || ((Number) sums.get("fee")).longValue() != day.totals().fee())
            throw new IllegalStateException("명세 줄 합계와 머리말 합계가 다르다");
        jdbc.update(
                "UPDATE p_pg_settlement_days SET status='RECEIVED',updated_at=LOCALTIMESTAMP WHERE id=? AND status='RECEIVING'",
                day.id());
    }

    @Override
    public List<StoredLine> unmatched(String provider, LocalDate date, int size) {
        return jdbc.query(
                LINES + " WHERE d.provider=? AND d.settlement_date=? AND NOT l.matched ORDER BY l.id LIMIT ?",
                this::line,
                provider,
                date,
                size);
    }

    @Override
    public List<StoredLine> unresolved(String provider, long after, int size) {
        return jdbc.query(LINES + """
                 JOIN p_pg_reconcile_results r ON r.line_id=l.id
                 WHERE d.provider=? AND d.status='POSTED' AND l.id>?
                   AND (r.status='OPEN' OR l.suspense_amount<>0 AND NOT l.adjusted)
                 ORDER BY l.id LIMIT ? FOR UPDATE OF l SKIP LOCKED
                """, this::line, provider, after, size);
    }

    @Override
    public void decide(StoredLine line, PgSettlement.Decision decision) {
        result(line.provider(), line.line().lineKey(), line.id(), line.date(), decision);
        if (!"POSTED".equals(line.dayStatus()))
            jdbc.update("""
                UPDATE p_pg_settlement_lines SET clearing_amount=?,suspense_amount=amount-?,matched=TRUE,
                  updated_at=LOCALTIMESTAMP WHERE id=?
                """, decision.clearingAmount(), decision.clearingAmount(), line.id());
    }

    private void result(String provider, String key, Long lineId, LocalDate observed, PgSettlement.Decision d) {
        named.update(RESULT_SQL, resultArgs(provider, key, lineId, observed, d));
    }

    private MapSqlParameterSource resultArgs(
            String provider, String key, Long lineId, LocalDate observed, PgSettlement.Decision d) {
        return new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("key", key)
                .addValue("line", lineId)
                .addValue("date", observed)
                .addValue("expectedDate", d.expectedDate())
                .addValue("amount", d.expectedAmount())
                .addValue("fee", d.expectedFee())
                .addValue("version", d.policyVersion())
                .addValue("finding", d.finding().name())
                .addValue("status", d.finding() == PgSettlement.Finding.MATCHED ? "RESOLVED" : "OPEN");
    }

    @Override
    public void decideBatch(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) return;
        // Note: JDBC executeBatch는 거래별 SQL을 보낸다. 페이지를 VALUES로 묶어 왕복과 실행 명령 수를 줄인다.
        for (int from = 0; from < verdicts.size(); from += 1000) {
            var chunk = verdicts.subList(from, Math.min(from + 1000, verdicts.size()));
            var args = new MapSqlParameterSource();
            var rows = new java.util.ArrayList<String>();
            var allocation = new java.util.ArrayList<Object>();
            for (int i = 0; i < chunk.size(); i++) {
                var v = chunk.get(i);
                var row = resultArgs(
                        v.line().provider(),
                        v.line().line().lineKey(),
                        v.line().id(),
                        v.line().date(),
                        v.decision());
                int index = i;
                row.getValues().forEach((key, value) -> args.addValue(key + index, value));
                rows.add("("
                        + java.util.stream.Stream.of(
                                        "provider",
                                        "key",
                                        "line",
                                        "date",
                                        "expectedDate",
                                        "amount",
                                        "fee",
                                        "version",
                                        "finding",
                                        "status")
                                .map(key -> ":" + key + index)
                                .collect(java.util.stream.Collectors.joining(","))
                        + ",CASE WHEN :status" + i + "='RESOLVED' THEN LOCALTIMESTAMP END,CASE WHEN :status" + i
                        + "='RESOLVED' THEN 'SYSTEM' END)");
                java.util.Collections.addAll(
                        allocation, v.line().id(), v.decision().clearingAmount());
            }
            String sql = RESULT_SQL.substring(0, RESULT_SQL.indexOf("VALUES")) + "VALUES " + String.join(",", rows)
                    + " " + RESULT_SQL.substring(RESULT_SQL.indexOf("ON CONFLICT"));
            if (named.update(sql, args) != chunk.size()) throw new IllegalStateException("판정 저장 건수가 맞지 않는다");
            int updated = jdbc.update(
                    """
                    UPDATE p_pg_settlement_lines l SET clearing_amount=v.amount,suspense_amount=l.amount-v.amount,
                      matched=TRUE,updated_at=LOCALTIMESTAMP FROM (VALUES
                    """ + String.join(",", java.util.Collections.nCopies(chunk.size(), "(?,?)"))
                            + ") v(id,amount) WHERE l.id=v.id",
                    allocation.toArray());
            if (updated != chunk.size()) throw new IllegalStateException("회계 배정 갱신 건수가 맞지 않는다");
        }
    }

    private static final String MANUAL_ACCEPTED = "stored.resolved_by IS NOT NULL AND stored.resolved_by<>'SYSTEM'"
            + " AND stored.finding=excluded.finding AND stored.expected_amount IS NOT DISTINCT FROM excluded.expected_amount"
            + " AND stored.expected_fee IS NOT DISTINCT FROM excluded.expected_fee"
            + " AND stored.expected_date IS NOT DISTINCT FROM excluded.expected_date"
            + " AND stored.policy_version IS NOT DISTINCT FROM excluded.policy_version";
    private static final String RESULT_SQL = """
                INSERT INTO p_pg_reconcile_results AS stored(provider,line_key,line_id,settlement_date,expected_date,
                  expected_amount,expected_fee,policy_version,finding,status,resolved_at,resolved_by)
                VALUES(:provider,:key,:line,:date,:expectedDate,:amount,:fee,:version,:finding,:status,
                  CASE WHEN :status='RESOLVED' THEN LOCALTIMESTAMP END,CASE WHEN :status='RESOLVED' THEN 'SYSTEM' END)
                ON CONFLICT(provider,line_key) DO UPDATE SET line_id=excluded.line_id,
                  settlement_date=excluded.settlement_date,expected_date=excluded.expected_date,
                  expected_amount=excluded.expected_amount,expected_fee=excluded.expected_fee,
                  policy_version=excluded.policy_version,finding=excluded.finding,
                  status=CASE WHEN {accepted} THEN stored.status ELSE excluded.status END,
                  resolved_at=CASE WHEN {accepted} THEN stored.resolved_at ELSE excluded.resolved_at END,
                  resolved_by=CASE WHEN {accepted} THEN stored.resolved_by ELSE excluded.resolved_by END,updated_at=LOCALTIMESTAMP
                """.replace("{accepted}", MANUAL_ACCEPTED);

    @Override
    public boolean contains(String provider, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_pg_settlement_lines WHERE provider=? AND line_key=?)",
                Boolean.class,
                provider,
                key));
    }

    @Override
    public java.util.Set<String> containsBatch(String provider, List<String> keys) {
        if (keys.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(named.queryForList(
                "SELECT line_key FROM p_pg_settlement_lines WHERE provider=:provider AND line_key IN (:keys)",
                Map.of("provider", provider, "keys", keys),
                String.class));
    }

    @Override
    public void missing(String provider, String key, PgSettlement.Decision decision, LocalDate observedDate) {
        result(provider, key, null, observedDate, decision);
    }

    @Override
    public void matched(String provider, LocalDate date) {
        jdbc.update(
                "UPDATE p_pg_settlement_days SET status='MATCHED',updated_at=LOCALTIMESTAMP WHERE provider=? AND settlement_date=? AND status='RECEIVED'",
                provider,
                date);
    }

    @Override
    public Day lockDay(String provider, LocalDate date) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "pg-settlement:" + provider);
        return jdbc.queryForObject(
                "SELECT * FROM p_pg_settlement_days WHERE provider=? AND settlement_date=? FOR UPDATE",
                (rs, n) -> new Day(
                        rs.getLong("id"),
                        rs.getString("provider"),
                        rs.getObject("settlement_date", LocalDate.class),
                        rs.getString("status"),
                        new PgSettlement.Totals(
                                rs.getLong("line_count"),
                                rs.getLong("amount"),
                                rs.getLong("fee"),
                                rs.getLong("carry_in"),
                                rs.getLong("payout"),
                                rs.getLong("carry_out"))),
                provider,
                date);
    }

    @Override
    public long clearing(long dayId) {
        return sum("clearing_amount", dayId);
    }

    @Override
    public void checkPostingOrder(Day day) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM p_pg_settlement_days WHERE provider=? AND
                  ((settlement_date<? AND status<>'POSTED') OR (settlement_date>? AND status='POSTED')))
                """, Boolean.class, day.provider(), day.date(), day.date())))
            throw new IllegalStateException("PG 정산 회계는 날짜 순서대로 처리해야 한다");
        var previous = jdbc.queryForList("""
                SELECT carry_out FROM p_pg_settlement_days WHERE provider=? AND settlement_date<? AND status='POSTED'
                ORDER BY settlement_date DESC LIMIT 1
                """, day.provider(), day.date());
        long carry = previous.isEmpty() ? 0 : ((Number) previous.get(0).get("carry_out")).longValue();
        if (carry != day.totals().carryIn()) throw new IllegalStateException("전일 PG 미상계액과 명세 이월액이 다르다");
    }

    @Override
    public long suspense(long dayId) {
        return sum("suspense_amount", dayId);
    }

    private long sum(String column, long dayId) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(" + column + "),0) FROM p_pg_settlement_lines WHERE day_id=?", Long.class, dayId);
    }

    @Override
    public void posted(long dayId) {
        jdbc.update(
                "UPDATE p_pg_settlement_days SET status='POSTED',updated_at=LOCALTIMESTAMP WHERE id=? AND status='MATCHED'",
                dayId);
    }

    @Override
    public void adjusted(long lineId, long amount) {
        jdbc.update(
                "UPDATE p_pg_settlement_lines SET clearing_amount=?,suspense_amount=0,adjusted=TRUE,updated_at=LOCALTIMESTAMP WHERE id=?",
                amount,
                lineId);
    }

    @Override
    public void closePairedSuspense(String provider) {
        // Note: SALE을 만들지 않는 AUTO_VOID 승인·취소는 가계정끼리 상쇄한다. 매일 같은 짝을 다시 쓰지 않는다.
        jdbc.update("""
                UPDATE p_pg_settlement_lines l SET adjusted=TRUE,updated_at=LOCALTIMESTAMP
                FROM (SELECT x.payment_key FROM p_pg_settlement_lines x
                  JOIN p_pg_settlement_days d ON d.id=x.day_id AND d.status='POSTED'
                  JOIN p_pg_reconcile_results r ON r.line_id=x.id
                  WHERE x.provider=? AND x.suspense_amount<>0 AND NOT x.adjusted
                  GROUP BY x.payment_key HAVING count(*)=2 AND sum(x.suspense_amount)=0
                    AND bool_and(r.status='RESOLVED')) pairs
                WHERE l.provider=? AND l.payment_key=pairs.payment_key AND l.suspense_amount<>0 AND NOT l.adjusted
                """, provider, provider);
    }

    @Override
    public List<Map<String, Object>> days(String provider, LocalDate after, int size) {
        return jdbc.queryForList(
                "SELECT id,provider,settlement_date,snapshot_id,line_count,amount,fee,carry_in,payout,carry_out,status FROM p_pg_settlement_days WHERE provider=? AND settlement_date>? ORDER BY settlement_date LIMIT ?",
                provider,
                after,
                size);
    }

    @Override
    public List<Map<String, Object>> findings(String provider, long after, int size, String status) {
        return jdbc.queryForList("""
                SELECT r.id,r.provider,r.line_key,r.settlement_date,r.expected_date,r.expected_amount,r.expected_fee,
                  r.policy_version,r.finding,r.status,r.reason,r.resolved_by,r.resolved_at,r.created_at,r.updated_at,
                  l.amount AS pg_amount,l.fee AS pg_fee,l.payment_key,l.type,l.suspense_amount,l.adjusted AS accounting_closed
                FROM p_pg_reconcile_results r LEFT JOIN p_pg_settlement_lines l ON l.id=r.line_id
                WHERE r.provider=? AND r.id>? AND r.status=? ORDER BY r.id LIMIT ?
                """, provider, after, status, size);
    }

    @Override
    public Balances balances() {
        return jdbc.queryForObject(
                """
                SELECT coalesce(sum(debit-credit) FILTER(WHERE account='CASH'),0) AS book_cash,
                  coalesce(sum(debit-credit) FILTER(WHERE account='PG_CLEARING'),0) AS pg_clearing,
                  coalesce(sum(credit-debit) FILTER(WHERE account='SELLER_PAYABLE'),0) AS seller_payable,
                  coalesce(sum(credit-debit) FILTER(WHERE account='PG_PAYABLE'),0) AS pg_payable,
                  coalesce(sum(credit-debit) FILTER(WHERE account='PG_SUSPENSE'),0) AS pg_suspense
                FROM p_ledger_entries
                """,
                (rs, n) -> new Balances(
                        rs.getLong("book_cash"),
                        rs.getLong("pg_clearing"),
                        rs.getLong("seller_payable"),
                        rs.getLong("pg_payable"),
                        rs.getLong("pg_suspense"),
                        false));
    }

    @Override
    public void resolve(long id, String reason, String actor) {
        var rows = jdbc.queryForList("SELECT * FROM p_pg_reconcile_results WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty())
            throw new com.sparta.ticket.common.exception.BusinessException(
                    com.sparta.ticket.common.exception.CommonErrorCode.NOT_FOUND);
        var row = rows.get(0);
        if (!"OPEN".equals(row.get("status"))
                || !("FEE_MISMATCH".equals(row.get("finding")) || "DATE_MISMATCH".equals(row.get("finding"))))
            throw new com.sparta.ticket.common.exception.BusinessException(
                    com.sparta.ticket.common.exception.CommonErrorCode.INVALID_STATUS_TRANSITION,
                    "금액·거래 불명은 원인을 해결한 뒤 다시 대사해야 한다");
        jdbc.update("""
                UPDATE p_pg_reconcile_results SET status='RESOLVED',reason=?,resolved_by=?,resolved_at=LOCALTIMESTAMP,
                  updated_at=LOCALTIMESTAMP,updated_by=?,resolution_history=resolution_history
                  ||jsonb_build_array(jsonb_build_object('actor',?::text,'reason',?::text,'at',LOCALTIMESTAMP,'finding',finding))
                WHERE id=?
                """, reason, actor, actor, actor, reason, id);
    }

    @Override
    public List<Map<String, Object>> metrics() {
        return jdbc.queryForList("""
                SELECT r.provider,r.finding,count(*) AS count,coalesce(sum(abs(coalesce(r.expected_amount,l.amount,0))),0) AS amount,
                  coalesce(extract(epoch FROM LOCALTIMESTAMP-min(r.created_at)),0) AS oldest_seconds
                FROM p_pg_reconcile_results r LEFT JOIN p_pg_settlement_lines l ON l.id=r.line_id
                WHERE r.status='OPEN' GROUP BY r.provider,r.finding
                UNION ALL
                SELECT provider,'SUSPENSE',count(*),sum(abs(balance)),
                  extract(epoch FROM LOCALTIMESTAMP-min(oldest)) FROM (
                    SELECT l.provider,l.payment_key,sum(l.suspense_amount) AS balance,min(l.created_at) AS oldest
                    FROM p_pg_settlement_lines l JOIN p_pg_settlement_days d ON d.id=l.day_id
                    WHERE l.suspense_amount<>0 AND NOT l.adjusted AND d.status='POSTED'
                    GROUP BY l.provider,l.payment_key HAVING sum(l.suspense_amount)<>0) s GROUP BY provider
                """);
    }

    private StoredLine line(ResultSet rs, int row) throws SQLException {
        return new StoredLine(
                rs.getLong("id"),
                rs.getLong("day_id"),
                rs.getString("provider"),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getString("day_status"),
                new PgSettlement.Line(
                        rs.getString("line_key"),
                        rs.getString("payment_key"),
                        rs.getString("order_id"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getLong("fee"),
                        rs.getObject("occurred_at", LocalDateTime.class)),
                rs.getLong("clearing_amount"),
                rs.getLong("suspense_amount"),
                rs.getBoolean("adjusted"));
    }
}
