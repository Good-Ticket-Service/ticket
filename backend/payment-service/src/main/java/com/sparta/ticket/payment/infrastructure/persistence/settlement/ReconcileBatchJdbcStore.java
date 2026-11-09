package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository.CheckResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DB 검사와 완료 마커를 원자적으로 저장한다. 업무 커밋 직후 종료돼도 재반영하지 않는다. */
@Repository
@Slf4j
public class ReconcileBatchJdbcStore {
    private final JdbcTemplate jdbc;
    private final SettlementReconcileJdbcAdapter checks;
    private final LocalDateTime paymentsSince;
    private final ReconcileIncrementalJdbc incremental;

    @Autowired
    public ReconcileBatchJdbcStore(
            JdbcTemplate jdbc,
            SettlementReconcileJdbcAdapter checks,
            @Value("${payment.settlement.reconcile.payments-since:}") String paymentsSince) {
        this(jdbc, checks, paymentsSince, true);
    }

    ReconcileBatchJdbcStore(
            JdbcTemplate jdbc, SettlementReconcileJdbcAdapter checks, String paymentsSince, boolean enabled) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.paymentsSince = paymentsSince.isBlank() ? null : LocalDateTime.parse(paymentsSince);
        this.incremental = enabled ? new ReconcileIncrementalJdbc(jdbc) : null;
    }

    public record DatabaseResult(long runId, boolean valid, boolean technicalFailure) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public DatabaseResult database(long jobInstanceId, String mode) {
        if (!java.util.Set.of("FULL", "DAILY").contains(mode)) throw new IllegalArgumentException("대조 모드가 올바르지 않다");
        var accepted = jdbc.queryForList(
                "SELECT id FROM p_settlement_reconcile_runs WHERE batch_job_instance_id=? AND db_valid",
                Long.class,
                jobInstanceId);
        if (!accepted.isEmpty()) return new DatabaseResult(accepted.get(0), true, false);
        long started = System.nanoTime();
        long runId = jdbc.queryForObject("""
                INSERT INTO p_settlement_reconcile_runs(status,started_at,batch_job_instance_id,mode)
                VALUES('RUNNING',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul',?,?) RETURNING id
                """, Long.class, jobInstanceId, mode);
        var state = incremental == null ? null : incremental.prepare(mode);
        List<CheckResult> results =
                checks.runChecksInSnapshot(paymentsSince, state == null ? Map.of() : incremental.replacements(state));
        boolean technical = results.stream().anyMatch(result -> !result.completed());
        long violations = results.stream()
                .filter(CheckResult::completed)
                .mapToLong(CheckResult::violations)
                .sum();
        boolean valid = !technical && violations == 0;
        if (state != null) {
            if (valid) incremental.accept(state, runId);
            jdbc.update(
                    """
                    UPDATE p_settlement_reconcile_runs SET mode=?,input_generation=?,output_generation=?,snapshot=?,
                      candidate_headers=?,delta_headers=?,delta_entries=?,changed_keys=?,cache_difference_count=?,
                      cache_difference_sample=?,cache_difference_debit=?,cache_difference_credit=?,baseline_reason=? WHERE id=?
                    """,
                    state.mode(),
                    state.generation(),
                    valid ? state.generation() + 1 : null,
                    state.snapshot(),
                    state.candidates(),
                    state.headers(),
                    state.entries(),
                    state.keys(),
                    state.differences(),
                    state.differenceSample(),
                    state.differenceDebit(),
                    state.differenceCredit(),
                    !valid
                            ? null
                            : !state.previousValid()
                                    ? "INITIAL_FULL"
                                    : state.differences() > 0 ? "FULL_REBASELINE" : state.mode(),
                    runId);
            if (valid && state.differences() > 0)
                log.error(
                        "[RECONCILE] FULL 캐시 재기준화. runId={}, generation={}, differences={}",
                        runId,
                        state.generation(),
                        state.differences());
        }
        for (CheckResult result : results) {
            jdbc.update(
                    """
                    INSERT INTO p_settlement_reconcile_results(run_id,check_name,completed,violations,sample,duration_ms,error)
                    VALUES(?,?,?,?,?,?,?)
                    """,
                    runId,
                    result.check(),
                    result.completed(),
                    result.violations(),
                    result.sample(),
                    result.durationMs(),
                    result.error());
        }
        jdbc.update(
                """
                UPDATE p_settlement_reconcile_runs SET db_valid=?,technical_failure=?,violations=?,
                  db_duration_ms=?,pending_excluded=(SELECT count(*) FROM p_settlement_jobs WHERE status='PENDING'),
                  status=?,finished_at=CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul' END,
                  error=CASE WHEN ? THEN NULL WHEN ? THEN 'DB_INCOMPLETE' ELSE 'FINANCIAL_VIOLATION' END WHERE id=?
                """,
                valid,
                technical,
                violations,
                (System.nanoTime() - started) / 1000000,
                valid ? "RUNNING" : "FAILED",
                valid,
                valid,
                technical,
                runId);
        return new DatabaseResult(runId, valid, technical);
    }

    public long acceptedRun(long jobInstanceId) {
        return jdbc.queryForObject(
                "SELECT id FROM p_settlement_reconcile_runs WHERE batch_job_instance_id=? AND db_valid",
                Long.class,
                jobInstanceId);
    }

    public List<CheckResult> results(long runId) {
        return jdbc.query(
                """
                SELECT check_name,violations,sample,duration_ms,error FROM p_settlement_reconcile_results
                WHERE run_id=? AND check_name<>'PAYOUT_PROVIDER_MISMATCH' ORDER BY check_name
                """,
                (rs, n) -> new CheckResult(
                        rs.getString(1),
                        rs.getObject(2, Long.class),
                        rs.getString(3),
                        rs.getObject(4, Long.class),
                        rs.getString(5)),
                runId);
    }

    public void recordHttp(long runId, long durationMs, boolean technical) {
        jdbc.update(
                "UPDATE p_settlement_reconcile_runs SET http_duration_ms=?,technical_failure=? WHERE id=?",
                durationMs,
                technical,
                runId);
    }
}
