package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 호출자가 연 하나의 RR에서 파생 집계만 갱신한다. 금융 원장·원천은 변경하지 않는다. */
final class ReconcileIncrementalJdbc {
    private final JdbcTemplate jdbc;

    ReconcileIncrementalJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record State(
            long generation,
            boolean previousValid,
            String snapshot,
            String mode,
            long candidates,
            long headers,
            long entries,
            long keys,
            long differences,
            String differenceSample,
            java.math.BigDecimal differenceDebit,
            java.math.BigDecimal differenceCredit) {}

    State prepare(String requestedMode) {
        var head = jdbc.queryForMap("SELECT generation,valid,snapshot FROM p_reconcile_checkpoint WHERE id=1");
        long generation = ((Number) head.get("generation")).longValue();
        boolean valid = (Boolean) head.get("valid");
        String previous = (String) head.get("snapshot");
        String snapshot = jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
        String mode = valid ? requestedMode : "FULL";
        long candidates = 0;
        if (valid) {
            candidates = jdbc.queryForObject(
                    "SELECT count(*) FROM p_ledger_transactions WHERE writer_xid>=pg_snapshot_xmin(?::pg_snapshot)",
                    Long.class,
                    previous);
            jdbc.update("""
                    CREATE TEMP TABLE reconcile_delta_headers ON COMMIT DROP AS
                    SELECT id,seller_id FROM p_ledger_transactions WHERE writer_xid>=pg_snapshot_xmin(?::pg_snapshot)
                    AND NOT pg_visible_in_snapshot(writer_xid,?::pg_snapshot)
                    """, previous, previous);
        } else
            jdbc.execute(
                    "CREATE TEMP TABLE reconcile_delta_headers ON COMMIT DROP AS SELECT id,seller_id FROM p_ledger_transactions WHERE false");
        jdbc.execute("CREATE UNIQUE INDEX ON reconcile_delta_headers(id)");
        jdbc.execute("ANALYZE reconcile_delta_headers");
        // Note: 작은 증분을 큰 entry 테이블과 해시 조인하면 전체 줄을 다시 읽을 수 있다.
        // 분개 키별 조회를 한 번 materialize하고 균형·계정 집계·건수에서 재사용한다.
        jdbc.execute("""
                CREATE TEMP TABLE reconcile_delta_entries ON COMMIT DROP AS
                SELECT e.* FROM reconcile_delta_headers d CROSS JOIN LATERAL (
                  SELECT id,transaction_id,account,debit,credit FROM p_ledger_entries WHERE transaction_id=d.id OFFSET 0
                ) e
                """);
        jdbc.execute("ANALYZE reconcile_delta_entries");
        jdbc.execute("CREATE TEMP TABLE reconcile_delta_balances ON COMMIT DROP AS "
                + balances("reconcile_delta_headers", "reconcile_delta_entries"));
        jdbc.execute("""
                CREATE TEMP TABLE reconcile_candidate_balances ON COMMIT DROP AS
                SELECT account,scope,owner_id,sum(debit)::bigint AS debit,sum(credit)::bigint AS credit FROM (
                  SELECT * FROM p_reconcile_account_balances UNION ALL SELECT * FROM reconcile_delta_balances
                ) b GROUP BY account,scope,owner_id
                """);
        long differences = 0;
        String sample = null;
        var differenceDebit = java.math.BigDecimal.ZERO;
        var differenceCredit = java.math.BigDecimal.ZERO;
        if ("FULL".equals(mode)) {
            jdbc.execute("CREATE TEMP TABLE reconcile_full_balances ON COMMIT DROP AS "
                    + balances("p_ledger_transactions", "p_ledger_entries"));
            if (valid) {
                jdbc.execute("""
                        CREATE TEMP TABLE reconcile_cache_differences ON COMMIT DROP AS
                        SELECT coalesce(c.account,f.account) AS account,coalesce(c.scope,f.scope) AS scope,
                          coalesce(c.owner_id,f.owner_id) AS owner_id,coalesce(c.debit,0)-coalesce(f.debit,0) AS debit_difference,
                          coalesce(c.credit,0)-coalesce(f.credit,0) AS credit_difference
                        FROM reconcile_candidate_balances c FULL JOIN reconcile_full_balances f USING(account,scope,owner_id)
                        WHERE coalesce(c.debit,0)<>coalesce(f.debit,0) OR coalesce(c.credit,0)<>coalesce(f.credit,0)
                        """);
                differences = jdbc.queryForObject("SELECT count(*) FROM reconcile_cache_differences", Long.class);
                differenceDebit = jdbc.queryForObject(
                        "SELECT coalesce(sum(abs(debit_difference)),0) FROM reconcile_cache_differences",
                        java.math.BigDecimal.class);
                differenceCredit = jdbc.queryForObject(
                        "SELECT coalesce(sum(abs(credit_difference)),0) FROM reconcile_cache_differences",
                        java.math.BigDecimal.class);
                sample = jdbc.queryForObject("""
                        SELECT json_agg(d)::text FROM (SELECT * FROM reconcile_cache_differences
                          ORDER BY account,scope,owner_id LIMIT 10) d
                        """, String.class);
            }
        }
        return new State(
                generation,
                valid,
                snapshot,
                mode,
                candidates,
                jdbc.queryForObject("SELECT count(*) FROM reconcile_delta_headers", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM reconcile_delta_entries", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM reconcile_delta_balances", Long.class),
                differences,
                sample,
                differenceDebit,
                differenceCredit);
    }

    private static String balances(String headers, String entries) {
        return """
                SELECT e.account,CASE WHEN e.account='PAYOUT_CLEARING' THEN 'GLOBAL'
                  WHEN t.seller_id IS NULL THEN 'UNKNOWN' ELSE 'SELLER' END AS scope,
                  CASE WHEN e.account='PAYOUT_CLEARING' THEN 0 ELSE coalesce(t.seller_id,0) END AS owner_id,
                  sum(e.debit)::bigint AS debit,sum(e.credit)::bigint AS credit
                """ + " FROM " + headers + " t JOIN " + entries + " e ON e.transaction_id=t.id"
                + " WHERE e.account IN ('SELLER_PAYABLE','SELLER_RECEIVABLE','PAYOUT_CLEARING') GROUP BY 1,2,3";
    }

    Map<String, String> replacements(State state) {
        if (!"DAILY".equals(state.mode())) return Map.of();
        return Map.of(
                "LEDGER_UNBALANCED", """
                SELECT d.id FROM reconcile_delta_headers d LEFT JOIN reconcile_delta_entries e ON e.transaction_id=d.id
                GROUP BY d.id HAVING count(e.id)<2 OR coalesce(sum(e.debit),0)<>coalesce(sum(e.credit),0)
                """,
                "SELLER_PAYABLE_BALANCE", """
                SELECT coalesce(l.owner_id,x.seller_id) FROM
                  (SELECT owner_id,scope,credit-debit AS balance FROM reconcile_candidate_balances WHERE account='SELLER_PAYABLE') l
                FULL JOIN (SELECT seller_id,sum(amount) AS expected FROM (
                  SELECT seller_id,seller_amount::bigint AS amount FROM p_settlement_items
                    WHERE kind='SALE' AND status IN ('PENDING','HELD')
                  UNION ALL SELECT seller_id,payable_amount FROM p_settlement_statements WHERE status='ISSUED'
                ) u GROUP BY seller_id) x ON l.scope='SELLER' AND l.owner_id=x.seller_id
                WHERE coalesce(l.balance,0)<>coalesce(x.expected,0)
                """,
                "SELLER_RECEIVABLE_BALANCE", """
                SELECT coalesce(l.owner_id,x.seller_id) FROM
                  (SELECT owner_id,scope,debit-credit AS balance FROM reconcile_candidate_balances WHERE account='SELLER_RECEIVABLE') l
                FULL JOIN (SELECT seller_id,sum(remaining) AS expected FROM p_settlement_items
                  WHERE kind='DEDUCTION' AND status='OPEN' GROUP BY seller_id) x
                  ON l.scope='SELLER' AND l.owner_id=x.seller_id
                WHERE coalesce(l.balance,0)<>coalesce(x.expected,0)
                """,
                "PAYOUT_CLEARING_BALANCE", """
                SELECT 0 FROM (SELECT
                  coalesce((SELECT sum(credit-debit) FROM reconcile_candidate_balances WHERE account='PAYOUT_CLEARING'),0) AS balance,
                  coalesce((SELECT sum(amount) FROM p_payouts WHERE status='DONE'),0)-
                  coalesce((SELECT sum(e.debit) FROM p_ledger_entries e JOIN p_ledger_transactions t ON t.id=e.transaction_id
                    WHERE t.source_type='PAYOUT_CASH' AND e.account='PAYOUT_CLEARING'),0) AS expected) c
                WHERE c.balance<>c.expected
                """);
    }

    void accept(State state, long runId) {
        int accepted = jdbc.update("""
                UPDATE p_reconcile_checkpoint SET generation=generation+1,valid=true,snapshot=?,run_id=?,accepted_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'
                WHERE id=1 AND generation=?
                """, state.snapshot(), runId, state.generation());
        if (accepted != 1) throw new IllegalStateException("RECONCILE_GENERATION_CONFLICT");
        if ("FULL".equals(state.mode())) {
            jdbc.update("DELETE FROM p_reconcile_account_balances");
            jdbc.update("INSERT INTO p_reconcile_account_balances SELECT * FROM reconcile_full_balances");
        } else jdbc.update("""
                INSERT INTO p_reconcile_account_balances SELECT * FROM reconcile_delta_balances
                ON CONFLICT(account,scope,owner_id) DO UPDATE SET
                  debit=p_reconcile_account_balances.debit+EXCLUDED.debit,
                  credit=p_reconcile_account_balances.credit+EXCLUDED.credit
                """);
    }
}
