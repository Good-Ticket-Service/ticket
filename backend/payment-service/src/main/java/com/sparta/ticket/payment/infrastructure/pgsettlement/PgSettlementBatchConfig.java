package com.sparta.ticket.payment.infrastructure.pgsettlement;

import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementClient;
import com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementProperties;
import com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementTransactions;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration
@EnableConfigurationProperties(PgSettlementProperties.class)
public class PgSettlementBatchConfig {
    @Bean
    public Job pgSettlementJob(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            PgSettlementClient client,
            PgSettlementTransactions transactions,
            PgSettlementProperties properties,
            Clock clock,
            PgSettlementMetrics metrics) {
        var noTransaction = new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        Step receive = new StepBuilder("pgSettlementReceive", repository)
                .tasklet(
                        (contribution, context) -> {
                            var step = contribution.getStepExecution();
                            var state = step.getExecutionContext();
                            if ("true".equals(state.getString("eof", "false"))) return RepeatStatus.FINISHED;
                            String provider = step.getJobParameters().getString("provider");
                            LocalDate date =
                                    LocalDate.parse(step.getJobParameters().getString("settlementDate"));
                            if (!date.isBefore(LocalDate.now(clock)) || date.isBefore(properties.startDate()))
                                throw new IllegalArgumentException("마감된 정산 시작일 이후 날짜만 수집할 수 있다");
                            String cursor = state.getString("cursor", "");
                            var page = client.read(
                                    provider, date, cursor, properties.pageSize(), state.getString("snapshot", ""));
                            if (page == null
                                    || !provider.equals(page.provider())
                                    || !date.equals(page.settlementDate())
                                    || page.lines().size() > properties.pageSize())
                                throw new IllegalStateException("명세 응답의 대상이 맞지 않는다");
                            String previous = cursor;
                            for (var line : page.lines()) {
                                if (line.lineKey().compareTo(previous) <= 0)
                                    throw new IllegalStateException("명세 cursor가 앞으로 진행하지 않는다");
                                previous = line.lineKey();
                            }
                            if (page.nextCursor() != null
                                    && (page.lines().isEmpty()
                                            || !page.nextCursor().equals(previous)))
                                throw new IllegalStateException("다음 cursor가 마지막 사건 키와 다르다");
                            var before = new org.springframework.batch.item.ExecutionContext();
                            state.entrySet().forEach(entry -> before.put(entry.getKey(), entry.getValue()));
                            try {
                                transactions.receive(page, step);
                            } catch (RuntimeException | Error e) {
                                // Note: 커밋 실패 뒤 프레임워크가 메모리의 앞선 cursor를 따로 저장하지 않게 되돌린다.
                                step.setExecutionContext(before);
                                throw e;
                            }
                            contribution.incrementReadCount();
                            contribution.incrementWriteCount(page.lines().size());
                            return page.nextCursor() == null ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
                        },
                        transactionManager)
                .transactionAttribute(noTransaction)
                .build();

        Step reconcile = new StepBuilder("pgSettlementReconcile", repository)
                .tasklet(
                        (contribution, context) -> {
                            var step = contribution.getStepExecution();
                            String provider = step.getJobParameters().getString("provider");
                            var state = step.getExecutionContext();
                            if (!"true".equals(state.getString("revisited", "false"))) {
                                long next = transactions.revisit(provider, state.getLong("revisitAfter", 0));
                                if (next >= 0) {
                                    state.putLong("revisitAfter", next);
                                    return RepeatStatus.CONTINUABLE;
                                }
                                state.putString("revisited", "true");
                            }
                            int processed = transactions.reconcile(
                                    provider,
                                    LocalDate.parse(step.getJobParameters().getString("settlementDate")));
                            if (processed > 0) {
                                contribution.incrementReadCount();
                                contribution.incrementWriteCount(processed);
                            }
                            return processed == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
                        },
                        transactionManager)
                .transactionAttribute(noTransaction)
                .build();
        Step missing = new StepBuilder("pgSettlementMissing", repository)
                .tasklet(
                        (contribution, context) -> {
                            var step = contribution.getStepExecution();
                            var state = step.getExecutionContext();
                            long next = transactions.missing(
                                    step.getJobParameters().getString("provider"),
                                    LocalDate.parse(step.getJobParameters().getString("settlementDate")),
                                    state.getLong("after", 0));
                            if (next >= 0) {
                                state.putLong("after", next);
                                contribution.incrementReadCount();
                            }
                            return next < 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
                        },
                        transactionManager)
                .transactionAttribute(noTransaction)
                .build();
        Step post = new StepBuilder("pgSettlementPost", repository)
                .tasklet(
                        (contribution, context) -> {
                            var step = contribution.getStepExecution();
                            transactions.post(
                                    step.getJobParameters().getString("provider"),
                                    LocalDate.parse(step.getJobParameters().getString("settlementDate")));
                            return RepeatStatus.FINISHED;
                        },
                        transactionManager)
                .transactionAttribute(noTransaction)
                .build();
        return new JobBuilder("pgSettlementJob", repository)
                .listener(metrics)
                .start(receive)
                .next(reconcile)
                .next(missing)
                .next(post)
                .build();
    }
}
