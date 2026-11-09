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
        // Note: 15초마다 도는 조회라 끝난 결제(DONE·FAILED·CANCELED)까지 세면 결제 수에 비례해 전체 스캔이 된다.
        // 미확정 상태만 세고, 상태마다 따로 조건을 써야 각 상태의 부분 인덱스(ready_expires·in_progress_next_attempt)를 탄다.
        jdbc.query("""
                SELECT pg_provider, 'READY', count(*) FROM p_payments
                WHERE deleted_at IS NULL AND status = 'READY' GROUP BY pg_provider
                UNION ALL
                SELECT pg_provider, 'IN_PROGRESS', count(*) FROM p_payments
                WHERE deleted_at IS NULL AND status = 'IN_PROGRESS' GROUP BY pg_provider
                """, rs -> {
            result.add(new Sample(
                    "payment.db.payments.unfinished",
                    List.of("provider", rs.getString(1), "status", rs.getString(2)),
                    rs.getDouble(3)));
        });
        // Note: 끝나지 않은 환불만 부분 인덱스(ix_p_refunds_unfinished)로 읽는다. 아래 제한 환불 조회도 같은 인덱스를 쓴다.
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
        // Note: next_reconcile_at이 있으면 승인 요청 시각도 있다(ck_p_payments_reconcile_state). 조건을 명시해야 부분 인덱스
        // ix_p_payments_reconcile을 쓰고, 빠지면 결제 전체를 스캔한다.
        jdbc.query("""
                SELECT count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(reconcile_unknown_since)))),0)
                FROM p_payments WHERE deleted_at IS NULL AND status='FAILED' AND approve_requested_at IS NOT NULL
                  AND next_reconcile_at IS NOT NULL AND reconcile_unknown_since IS NOT NULL
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
        readSettlement(result);
        return List.copyOf(result);
    }

    /** 지급 볼 이유. 운영 조회(PayoutOperationQueriesAdapter)와 같은 분류다. DONE은 위험이 아니라 빼고 센다. */
    static final List<String> PAYOUT_ATTENTIONS = List.of(
            "ACCOUNT_MISSING",
            "GATE_BLOCKED",
            "READY_UNSCHEDULED",
            "READY",
            "RESULT_UNKNOWN",
            "INQUIRY_ONLY",
            "IN_PROGRESS",
            "FAILED");

    static final List<String> PAYOUT_FAILURES = List.of("ACCOUNT_INVALID", "REJECTED", "REQUEST_INVALID");
    static final List<String> HOLD_REASONS =
            List.of("REFUND_IN_PROGRESS", "MISSING_SCHEDULE_START", "CALENDAR_NOT_COVERED");
    static final List<String> SETTLEMENT_TASKS = List.of("statement", "payout", "payout_recovery", "settlement_job");

    /**
     * 정산 위험 지표(Task 20-11, settlement-design 8.3절). 건수·금액·최장 나이만 내고 판매자·지급 ID는 라벨에 넣지 않는다.
     * 나이 기준: READY 계열은 지급 생성, REQUESTED 계열은 최초 요청, FAILED는 실패 확정(마지막 변경) 시각부터.
     */
    private void readSettlement(List<Sample> result) {
        jdbc.query(
                """
                SELECT attention, count(*), coalesce(sum(amount), 0),
                  coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(since)))), 0)
                FROM (
                  SELECT amount,
                    CASE
                      WHEN status = 'READY' AND last_error = 'ACCOUNT_NOT_REGISTERED' THEN 'ACCOUNT_MISSING'
                      WHEN status = 'READY' AND last_error LIKE 'GATE%' THEN 'GATE_BLOCKED'
                      WHEN status = 'READY' AND next_attempt_at IS NULL THEN 'READY_UNSCHEDULED'
                      WHEN status = 'READY' THEN 'READY'
                      WHEN status = 'REQUESTED' AND inquiry_only THEN 'INQUIRY_ONLY'
                      WHEN status = 'REQUESTED' AND provider_status IN ('ACCEPTED', 'PROCESSING') THEN 'IN_PROGRESS'
                      WHEN status = 'REQUESTED' THEN 'RESULT_UNKNOWN'
                      ELSE status
                    END AS attention,
                    CASE WHEN status = 'READY' THEN created_at WHEN status = 'REQUESTED' THEN requested_at
                      ELSE updated_at END AS since
                  FROM p_payouts WHERE status <> 'DONE') p
                GROUP BY attention
                """,
                rs -> {
                    var tags = List.of("attention", rs.getString(1));
                    result.add(new Sample("settlement.db.payouts", tags, rs.getDouble(2)));
                    result.add(new Sample("settlement.db.payouts.amount.won", tags, rs.getDouble(3)));
                    result.add(new Sample("settlement.db.payouts.oldest.age.seconds", tags, rs.getDouble(4)));
                });
        jdbc.query("""
                SELECT failure_code, count(*) FROM p_payouts WHERE status = 'FAILED' GROUP BY failure_code
                """, rs -> {
            result.add(new Sample(
                    "settlement.db.payouts.failed", List.of("failure_code", rs.getString(1)), rs.getDouble(2)));
        });
        // 실행 시각이 지났는데 처리하지 않은 정산 작업. 발행은 정산 가능일 04:00에 도는 일 1회 잡이라 그 시각부터 센다
        // Note: eligible_at에 계산을 붙이면 인덱스 범위 검색이 안 돼 PENDING 부분 인덱스 전체를 읽는다. 비교 쪽에서 4시간을 뺀다.
        jdbc.query("""
                SELECT 'statement', count(*),
                  coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(eligible_at + interval '4 hours')))), 0)
                FROM p_settlement_items WHERE kind = 'SALE' AND status = 'PENDING'
                  AND eligible_at <= localtimestamp - interval '4 hours'
                UNION ALL
                SELECT 'payout', count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(next_attempt_at)))), 0)
                FROM p_payouts WHERE status = 'READY' AND next_attempt_at <= localtimestamp
                UNION ALL
                SELECT 'payout_recovery', count(*),
                  coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(next_attempt_at)))), 0)
                FROM p_payouts WHERE status = 'REQUESTED' AND next_attempt_at <= localtimestamp
                UNION ALL
                SELECT 'settlement_job', count(*),
                  coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(next_attempt_at)))), 0)
                FROM p_settlement_jobs WHERE status = 'PENDING' AND next_attempt_at <= localtimestamp
                """, rs -> {
            var tags = List.of("task", rs.getString(1));
            result.add(new Sample("settlement.db.jobs.due", tags, rs.getDouble(2)));
            result.add(new Sample("settlement.db.jobs.oldest.due.age.seconds", tags, rs.getDouble(3)));
        });
        // 정산 반영 작업(#304): 격리 건수와 아직 정산에 반영되지 않은 결제 금액. 미완료만 부분 인덱스로 읽는다.
        // Note: 승인 작업만 금액에 넣는다. 환불 완료 작업은 같은 결제의 반대 분개라 금액을 다시 더하지 않는다.
        jdbc.query("""
                SELECT count(*) FILTER (WHERE j.status = 'BLOCKED'),
                  coalesce(sum(p.amount) FILTER (WHERE j.reason = 'PAYMENT_APPROVED'), 0)
                FROM p_settlement_jobs j JOIN p_payments p ON p.id = j.payment_id
                WHERE j.status IN ('PENDING', 'BLOCKED')
                """, rs -> {
            result.add(new Sample("settlement.db.jobs.blocked", List.of(), rs.getDouble(1)));
            result.add(new Sample("settlement.db.jobs.unreflected.amount.won", List.of(), rs.getDouble(2)));
        });
        // Note: UNPOSTED·HELD는 드문 상태라 부분 인덱스(ix_p_settlement_items_unposted_sale·held_sale)로 해당 행만 읽는다.
        jdbc.query("""
                SELECT count(*) FROM p_settlement_items WHERE kind = 'SALE' AND status = 'UNPOSTED'
                """, rs -> {
            result.add(new Sample("settlement.db.sales.unposted", List.of(), rs.getDouble(1)));
        });
        jdbc.query("""
                SELECT hold_reason, count(*), coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(updated_at)))), 0)
                FROM p_settlement_items WHERE kind = 'SALE' AND status = 'HELD' GROUP BY hold_reason
                """, rs -> {
            var tags = List.of("reason", rs.getString(1));
            result.add(new Sample("settlement.db.sales.held", tags, rs.getDouble(2)));
            result.add(new Sample("settlement.db.sales.held.oldest.age.seconds", tags, rs.getDouble(3)));
        });
        jdbc.query("""
                SELECT count(*), coalesce(sum(remaining), 0),
                  coalesce(greatest(0, extract(epoch FROM (localtimestamp - min(created_at)))), 0)
                FROM p_settlement_items WHERE kind = 'DEDUCTION' AND status = 'OPEN'
                """, rs -> {
            result.add(new Sample("settlement.db.deductions.open", List.of(), rs.getDouble(1)));
            result.add(new Sample("settlement.db.deductions.open.amount.won", List.of(), rs.getDouble(2)));
            result.add(new Sample("settlement.db.deductions.oldest.age.seconds", List.of(), rs.getDouble(3)));
        });
        // 마지막 성공 대조 시각. 실행 기록이 DB에 있어 재시작·인스턴스와 무관하게 같은 값이다
        jdbc.query("""
                SELECT extract(epoch FROM (finished_at AT TIME ZONE 'Asia/Seoul')), provider_unreachable
                FROM p_settlement_reconcile_runs WHERE status = 'SUCCEEDED'
                ORDER BY finished_at DESC, id DESC LIMIT 1
                """, rs -> {
            result.add(new Sample("settlement.reconcile.last.success.timestamp.seconds", List.of(), rs.getDouble(1)));
        });
        // 항목별 결과는 결과를 남긴 마지막 실행에서 읽는다(#310). 일부 항목이 미완료인 FAILED 실행도 끝난 항목의 위반은 경보에 올린다.
        // 미완료 항목은 위반 수 대신 미완료 표시를 낸다. 결과가 없는 실패(트랜잭션 자체 실패)는 건너뛰고 그 전 실행을 본다
        jdbc.query("""
                SELECT r.check_name, r.completed, r.violations, r.duration_ms FROM p_settlement_reconcile_results r
                WHERE r.run_id = (SELECT s.id FROM p_settlement_reconcile_runs s
                                  WHERE s.status <> 'RUNNING'
                                    AND EXISTS (SELECT 1 FROM p_settlement_reconcile_results x WHERE x.run_id = s.id)
                                  ORDER BY s.finished_at DESC, s.id DESC LIMIT 1)
                """, rs -> {
            List<String> check = List.of("check", rs.getString(1));
            boolean completed = rs.getBoolean(2);
            if (completed) result.add(new Sample("settlement.reconcile.violations", check, rs.getDouble(3)));
            result.add(new Sample("settlement.reconcile.check.incomplete", check, completed ? 0 : 1));
            long duration = rs.getLong(4);
            if (!rs.wasNull())
                result.add(new Sample("settlement.reconcile.check.duration.seconds", check, duration / 1000.0));
        });
        jdbc.query("""
                SELECT CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END,coalesce(provider_unreachable,0),
                  coalesce(candidate_headers,0),coalesce(delta_headers,0),coalesce(delta_entries,0),coalesce(changed_keys,0),
                  coalesce(db_duration_ms,0),coalesce(http_duration_ms,0),coalesce(pending_excluded,0),coalesce(cache_difference_count,0)
                FROM p_settlement_reconcile_runs
                WHERE status <> 'RUNNING' ORDER BY finished_at DESC, id DESC LIMIT 1
                """, rs -> {
            result.add(new Sample("settlement.reconcile.last.failed", List.of(), rs.getDouble(1)));
            result.add(new Sample("settlement.reconcile.provider.unreachable", List.of(), rs.getDouble(2)));
            result.add(new Sample("settlement.reconcile.candidate.headers", List.of(), rs.getDouble(3)));
            result.add(new Sample("settlement.reconcile.delta.headers", List.of(), rs.getDouble(4)));
            result.add(new Sample("settlement.reconcile.delta.entries", List.of(), rs.getDouble(5)));
            result.add(new Sample("settlement.reconcile.changed.keys", List.of(), rs.getDouble(6)));
            result.add(new Sample("settlement.reconcile.db.duration.seconds", List.of(), rs.getDouble(7) / 1000));
            result.add(new Sample("settlement.reconcile.http.duration.seconds", List.of(), rs.getDouble(8) / 1000));
            result.add(new Sample("settlement.reconcile.pending.excluded", List.of(), rs.getDouble(9)));
        });
        jdbc.query(
                "SELECT h.valid,h.generation,coalesce(greatest(0,extract(epoch FROM (LOCALTIMESTAMP-h.accepted_at))),0),CASE WHEN h.valid THEN coalesce(r.cache_difference_count,0) ELSE 0 END FROM p_reconcile_checkpoint h LEFT JOIN p_settlement_reconcile_runs r ON r.id=h.run_id WHERE h.id=1",
                rs -> {
                    result.add(new Sample("settlement.reconcile.baseline.valid", List.of(), rs.getBoolean(1) ? 1 : 0));
                    result.add(new Sample("settlement.reconcile.baseline.generation", List.of(), rs.getDouble(2)));
                    result.add(new Sample("settlement.reconcile.baseline.age.seconds", List.of(), rs.getDouble(3)));
                    result.add(new Sample("settlement.reconcile.cache.differences", List.of(), rs.getDouble(4)));
                });
    }
}
