package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.application.service.settlement.SettlementReconciliationJob;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.ReconcileBatchJdbcStore;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration
public class ReconcileBatchConfig {
    @Bean
    public Job reconcileJob(
            JobRepository repository,
            PlatformTransactionManager manager,
            ReconcileBatchJdbcStore store,
            SettlementReconciliationJob reconciliation) {
        var outside = new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        Step database = new StepBuilder("reconcileDatabase", repository)
                .tasklet(
                        (contribution, context) -> {
                            var execution = contribution.getStepExecution().getJobExecution();
                            var result = store.database(
                                    execution.getJobInstance().getInstanceId(),
                                    execution.getJobParameters().getString("mode"));
                            if (!result.valid())
                                throw new IllegalStateException(
                                        result.technicalFailure() ? "DB_INCOMPLETE" : "FINANCIAL_VIOLATION");
                            return RepeatStatus.FINISHED;
                        },
                        manager)
                .transactionAttribute(outside)
                .build();
        Step provider = new StepBuilder("reconcileProvider", repository)
                .tasklet(
                        (contribution, context) -> {
                            long instance = contribution
                                    .getStepExecution()
                                    .getJobExecution()
                                    .getJobInstance()
                                    .getInstanceId();
                            long runId = store.acceptedRun(instance);
                            long started = System.nanoTime();
                            var result = reconciliation.runProvider(runId, store.results(runId));
                            store.recordHttp(
                                    runId, (System.nanoTime() - started) / 1000000, result.providerUnreachable() > 0);
                            if (result.providerUnreachable() > 0 || result.violations() > 0)
                                throw new IllegalStateException("PROVIDER_INCOMPLETE_OR_MISMATCH");
                            return RepeatStatus.FINISHED;
                        },
                        manager)
                .transactionAttribute(outside)
                .build();
        return new JobBuilder("reconcileJob", repository)
                .start(database)
                .next(provider)
                .build();
    }
}
