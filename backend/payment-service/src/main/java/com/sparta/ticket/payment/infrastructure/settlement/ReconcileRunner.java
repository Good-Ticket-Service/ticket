package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.port.settlement.ReconcileExecution;
import com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementRecoveryTransactions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ReconcileRunner implements ReconcileExecution, DisposableBean {
    private final Job job;
    private final JobExplorer explorer;
    private final JobRepository repository;
    private final PgSettlementRecoveryTransactions recovery;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ThreadPoolTaskExecutor executor;
    private final TaskExecutorJobLauncher launcher;

    public ReconcileRunner(
            @Qualifier("reconcileJob") Job job,
            JobRepository repository,
            JobExplorer explorer,
            PgSettlementRecoveryTransactions recovery,
            JdbcTemplate jdbc,
            Clock clock)
            throws Exception {
        this.job = job;
        this.explorer = explorer;
        this.repository = repository;
        this.recovery = recovery;
        this.jdbc = jdbc;
        this.clock = clock;
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("settlement-reconcile-");
        executor.initialize();
        launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
    }

    public static JobParameters scheduledParameters(LocalDate date, String mode) {
        return new JobParametersBuilder()
                .addString("businessDate", date.toString())
                .addString("mode", mode)
                .addString("runKey", "SCHEDULED")
                .toJobParameters();
    }

    Execution scheduled(LocalDate date, String mode) {
        return launch(scheduledParameters(date, mode));
    }

    @Override
    public Execution run(LocalDate date, UUID commandId, String reason, String actor) {
        if (date == null
                || date.isAfter(LocalDate.now(clock))
                || commandId == null
                || reason == null
                || reason.isBlank()
                || reason.length() > 500
                || actor == null
                || actor.isBlank()) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        jdbc.update("""
                INSERT INTO p_reconcile_commands(command_id,business_date,mode,reason,actor,created_at)
                VALUES(?,?,'FULL',?,?,CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') ON CONFLICT DO NOTHING
                """, commandId, date, reason, actor);
        var command = jdbc.queryForMap(
                "SELECT business_date,reason,actor FROM p_reconcile_commands WHERE command_id=?", commandId);
        if (!date.equals(((java.sql.Date) command.get("business_date")).toLocalDate())
                || !reason.equals(command.get("reason"))
                || !actor.equals(command.get("actor")))
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "같은 명령 ID의 입력이 다르다");
        var parameters = new JobParametersBuilder()
                .addString("businessDate", date.toString())
                .addString("mode", "FULL")
                .addString("runKey", "MANUAL:" + commandId)
                .addString("actor", actor, false)
                .addString("reason", reason, false)
                .toJobParameters();
        if (repository.getLastJobExecution("reconcileJob", parameters) != null)
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "기존 실행 조회 또는 retry를 사용한다");
        return launch(parameters);
    }

    private Execution launch(JobParameters parameters) {
        try {
            var execution = launcher.run(job, parameters);
            return new Execution(execution.getId(), execution.getStatus().name());
        } catch (JobExecutionException
                | org.springframework.dao.ConcurrencyFailureException
                | org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "실행 중이거나 완료된 대조 작업이다");
        }
    }

    private JobExecution execution(long id) {
        var execution = explorer.getJobExecution(id);
        if (execution == null
                || !"reconcileJob".equals(execution.getJobInstance().getJobName()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return execution;
    }

    @Override
    public Execution retry(long id) {
        var execution = execution(id);
        if (execution.getStatus() != BatchStatus.FAILED && execution.getStatus() != BatchStatus.STOPPED)
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        return launch(execution.getJobParameters());
    }

    @Override
    public Execution recover(long id, String reason, String actor, boolean processTerminated) {
        var old = recovery.recoverForJob(id, reason, actor, processTerminated, "reconcileJob");
        return launch(old.getJobParameters());
    }

    @Override
    public Map<String, Object> progress(long id) {
        var execution = execution(id);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("executionId", id);
        progress.put("status", execution.getStatus().name());
        progress.put("businessDate", execution.getJobParameters().getString("businessDate"));
        progress.put("mode", execution.getJobParameters().getString("mode"));
        progress.put(
                "steps",
                execution.getStepExecutions().stream()
                        .map(s -> Map.of(
                                "name", s.getStepName(), "status", s.getStatus().name()))
                        .toList());
        var runs = jdbc.queryForList(
                "SELECT * FROM p_settlement_reconcile_runs WHERE batch_job_instance_id=? ORDER BY id DESC",
                execution.getJobInstance().getInstanceId());
        progress.put("runs", runs.stream().map(ReconcileRunner::camelCase).toList());
        progress.put("effectiveMode", runs.isEmpty() ? null : runs.get(0).get("mode"));
        if (!runs.isEmpty())
            progress.put(
                    "checks",
                    jdbc
                            .queryForList(
                                    "SELECT * FROM p_settlement_reconcile_results WHERE run_id=? ORDER BY check_name",
                                    runs.get(0).get("id"))
                            .stream()
                            .map(ReconcileRunner::camelCase)
                            .toList());
        progress.put(
                "baseline",
                camelCase(jdbc.queryForMap(
                        "SELECT generation,valid,run_id,accepted_at FROM p_reconcile_checkpoint WHERE id=1")));
        return progress;
    }

    private static Map<String, Object> camelCase(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char character : key.toCharArray()) {
                if (character == '_') upper = true;
                else {
                    name.append(upper ? Character.toUpperCase(character) : character);
                    upper = false;
                }
            }
            result.put(
                    name.toString(),
                    value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : value);
        });
        return result;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
