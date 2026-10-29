package com.sparta.ticket.payment.infrastructure.observation;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentObservationTransactions {
    private final JdbcTemplate jdbc;

    public PaymentObservationTransactions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Sample(String name, List<String> tags, double value) {}

    // Note: 같은 DB 스냅샷에서 읽되 운영 조회가 금융 작업의 연결을 오래 점유하지 않게 제한한다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public List<Sample> read() {
        jdbc.execute("SET LOCAL TIME ZONE 'Asia/Seoul'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        List<Sample> result = new ArrayList<>();
        jdbc.query("""
                SELECT pg_provider, status, count(*) AS count
                FROM p_payments WHERE deleted_at IS NULL GROUP BY pg_provider, status
                """, rs -> {
            result.add(new Sample(
                    "payment.db.payments",
                    List.of("provider", rs.getString(1), "status", rs.getString(2)),
                    rs.getDouble(3)));
        });
        jdbc.query("""
                SELECT p.pg_provider, r.status, r.recovery_mode, r.reason,
                  count(*) AS count, sum(r.refund_amount::bigint) AS amount,
                  greatest(0, extract(epoch FROM (localtimestamp - min(r.created_at)))) AS age,
                  greatest(0, extract(epoch FROM (localtimestamp - min(r.restriction_started_at)))) AS restricted_age,
                  count(*) FILTER (WHERE r.recovery_mode IN ('VERIFY_ONLY','PAUSED') AND r.restriction_started_at IS NULL) AS unknown_restriction_time
                FROM p_refunds r JOIN p_payments p ON p.id = r.payment_id
                WHERE r.deleted_at IS NULL AND p.deleted_at IS NULL AND r.status IN ('WAITING','REQUESTED','FAILED')
                GROUP BY p.pg_provider, r.status, r.recovery_mode, r.reason
                """, rs -> {
            var tags = List.of(
                    "provider",
                    rs.getString(1),
                    "status",
                    rs.getString(2),
                    "mode",
                    rs.getString(3),
                    "reason",
                    rs.getString(4));
            result.add(new Sample("payment.db.refunds.pending", tags, rs.getDouble(5)));
            result.add(new Sample("payment.db.refunds.exposure.won", tags, rs.getDouble(6)));
            result.add(new Sample("payment.db.refunds.oldest.record.age.seconds", tags, rs.getDouble(7)));
            result.add(new Sample("payment.db.refunds.oldest.restriction.age.seconds", tags, rs.getDouble(8)));
            result.add(new Sample("payment.db.refunds.restriction.time.unknown", tags, rs.getDouble(9)));
        });
        jdbc.query("""
                SELECT 'recovery' AS job, count(*) AS count,
                  coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(next_attempt_at)))),0) AS age
                FROM p_payments WHERE deleted_at IS NULL AND status='IN_PROGRESS' AND next_attempt_at <= localtimestamp
                UNION ALL
                SELECT 'reconcile', count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(next_reconcile_at)))),0)
                FROM p_payments WHERE deleted_at IS NULL AND status='FAILED' AND approve_requested_at IS NOT NULL AND next_reconcile_at <= localtimestamp
                UNION ALL
                SELECT 'refund', count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(next_attempt_at)))),0)
                FROM p_refunds WHERE deleted_at IS NULL AND status IN ('REQUESTED','FAILED')
                  AND recovery_mode <> 'PAUSED' AND NOT recovery_blocked AND next_attempt_at <= localtimestamp
                UNION ALL
                SELECT 'ready_expiry', count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(expires_at)))),0)
                FROM p_payments WHERE deleted_at IS NULL AND status='READY' AND expires_at <= localtimestamp
                """, rs -> {
            var tags = List.of("task", rs.getString(1));
            result.add(new Sample("payment.db.jobs.due", tags, rs.getDouble(2)));
            result.add(new Sample("payment.db.jobs.oldest.due.age.seconds", tags, rs.getDouble(3)));
        });
        jdbc.query("""
                SELECT count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(reconcile_unknown_since)))),0)
                FROM p_payments WHERE deleted_at IS NULL AND status='FAILED' AND next_reconcile_at IS NOT NULL AND reconcile_unknown_since IS NOT NULL
                """, rs -> {
            result.add(new Sample("payment.db.reconcile.unknown", List.of(), rs.getDouble(1)));
            result.add(new Sample("payment.db.reconcile.oldest.unknown.age.seconds", List.of(), rs.getDouble(2)));
        });
        jdbc.query(
                """
                SELECT p.pg_provider,
                  CASE WHEN r.verification_reason LIKE 'PG_INCONSISTENT_%' THEN 'inconsistent'
                    WHEN r.verification_reason IS NOT NULL THEN 'verification'
                    WHEN r.status='FAILED' THEN 'declined' ELSE 'other' END AS cause,
                  count(*) FROM p_refunds r JOIN p_payments p ON p.id=r.payment_id
                WHERE r.deleted_at IS NULL AND p.deleted_at IS NULL AND r.status IN ('WAITING','REQUESTED','FAILED')
                  AND r.recovery_mode IN ('VERIFY_ONLY','PAUSED') GROUP BY p.pg_provider,cause
                """,
                rs -> {
                    result.add(new Sample(
                            "payment.db.refunds.restricted",
                            List.of("provider", rs.getString(1), "cause", rs.getString(2)),
                            rs.getDouble(3)));
                });
        return List.copyOf(result);
    }
}
