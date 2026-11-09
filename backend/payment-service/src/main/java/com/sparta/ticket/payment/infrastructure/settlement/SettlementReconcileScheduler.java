package com.sparta.ticket.payment.infrastructure.settlement;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KST 최근 도래한 03시 슬롯을 확인한다. Batch가 다중 인스턴스의 같은 정기 키를 보호한다.
 * 기술 실패만 같은 JobInstance를 제한 재시도하고 금융 위반은 운영 조치를 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.reconcile.enabled", havingValue = "true")
public class SettlementReconcileScheduler {
    private final ReconcileRunner runner;
    private final JobRepository repository;
    private final JobExplorer explorer;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 기동 1분 뒤 보충하고 매분 기술적 실패의 제한 재시도를 확인한다. */
    @Scheduled(
            initialDelayString = "${payment.settlement.reconcile.startup-delay-ms:60000}",
            fixedDelayString = "${payment.settlement.reconcile.schedule-delay-ms:60000}")
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(cron = "${payment.settlement.reconcile.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void reconcile() {
        var now = LocalDateTime.now(clock);
        var date = now.toLocalTime().isBefore(LocalTime.of(3, 0))
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        String mode = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "FULL" : "DAILY";
        boolean covered = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM p_settlement_reconcile_runs r
                JOIN batch_job_execution e ON e.job_instance_id=r.batch_job_instance_id
                JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id
                WHERE r.db_valid AND r.mode='FULL' AND r.status='SUCCEEDED'
                  AND p.parameter_name='businessDate' AND p.parameter_value=? AND r.started_at>=?)
                """, Boolean.class, date.toString(), java.sql.Timestamp.valueOf(date.atTime(3, 0))));
        if (covered) return;
        var last = repository.getLastJobExecution("reconcileJob", ReconcileRunner.scheduledParameters(date, mode));
        if (last != null) {
            if (last.isRunning()
                    || last.getStatus() == BatchStatus.COMPLETED
                    || last.getEndTime() == null
                    || last.getEndTime().plusMinutes(30).isAfter(now)
                    || explorer.getJobExecutions(last.getJobInstance()).size() >= 4) return;
            var runs = jdbc.queryForList(
                    "SELECT status,technical_failure FROM p_settlement_reconcile_runs WHERE batch_job_instance_id=? ORDER BY id DESC LIMIT 1",
                    last.getJobInstance().getInstanceId());
            if (!runs.isEmpty()
                    && !Boolean.TRUE.equals(runs.get(0).get("technical_failure"))
                    && !"RUNNING".equals(runs.get(0).get("status"))) return;
        }
        try {
            runner.scheduled(date, mode);
        } catch (RuntimeException e) {
            // Note: 실패는 실행 기록과 로그에 이미 남았다. 스케줄러가 다음 실행을 계속하게 삼킨다.
            log.debug("정산 대조 실패를 기록했다", e);
        }
    }
}
