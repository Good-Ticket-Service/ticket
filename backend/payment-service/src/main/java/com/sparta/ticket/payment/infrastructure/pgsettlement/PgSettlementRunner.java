package com.sparta.ticket.payment.infrastructure.pgsettlement;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementExecution;
import com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class PgSettlementRunner implements PgSettlementExecution, org.springframework.beans.factory.DisposableBean {
    private final Job job;
    private final TaskExecutorJobLauncher launcher;
    private final ThreadPoolTaskExecutor executor;
    private final PgSettlementRecoveryTransactions recovery;
    private final PgSettlementProperties properties;
    private final Clock clock;
    private final org.springframework.batch.core.explore.JobExplorer explorer;

    public PgSettlementRunner(
            @Qualifier("pgSettlementJob") Job job,
            JobRepository repository,
            PgSettlementRecoveryTransactions recovery,
            PgSettlementProperties properties,
            Clock clock,
            org.springframework.batch.core.explore.JobExplorer explorer)
            throws Exception {
        this.job = job;
        this.recovery = recovery;
        this.properties = properties;
        this.clock = clock;
        this.explorer = explorer;
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("pg-settlement-");
        executor.initialize();
        launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
    }

    @Override
    public Execution run(String provider, LocalDate date) {
        if (!Set.of("TOSS", "KAKAO").contains(provider)
                || date == null
                || !date.isBefore(LocalDate.now(clock))
                || date.isBefore(properties.startDate())) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        try {
            var execution = launcher.run(
                    job,
                    new JobParametersBuilder()
                            .addString("provider", provider)
                            .addString("settlementDate", date.toString())
                            .toJobParameters());
            return new Execution(execution.getId(), execution.getStatus().name());
        } catch (JobExecutionException e) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "실행 중이거나 이미 완료된 정산일이다");
        }
    }

    @Override
    public Execution recover(long id, String reason, String actor, boolean processTerminated) {
        var old = recovery.recover(id, reason, actor, processTerminated);
        return run(
                old.getJobParameters().getString("provider"),
                LocalDate.parse(old.getJobParameters().getString("settlementDate")));
    }

    @Override
    public Progress progress(long id) {
        var execution = explorer.getJobExecution(id);
        if (execution == null
                || !"pgSettlementJob".equals(execution.getJobInstance().getJobName()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return new Progress(
                id,
                execution.getJobParameters().getString("provider"),
                execution.getJobParameters().getString("settlementDate"),
                execution.getStatus().name(),
                execution.getStepExecutions().stream()
                        .map(s -> new StepProgress(
                                s.getStepName(), s.getStatus().name(), s.getReadCount(), s.getWriteCount()))
                        .toList());
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
