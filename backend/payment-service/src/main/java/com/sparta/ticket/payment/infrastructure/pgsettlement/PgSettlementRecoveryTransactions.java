package com.sparta.ticket.payment.infrastructure.pgsettlement;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 자동 시간 판정으로 살아 있는 실행을 빼앗지 않는다. 운영자가 프로세스 종료를 확인한 뒤만 호출한다. */
@Component
@RequiredArgsConstructor
public class PgSettlementRecoveryTransactions {
    private final JobExplorer explorer;
    private final JobRepository repository;
    private final Clock clock;

    @Transactional
    public JobExecution recover(long id, String reason, String actor, boolean processTerminated) {
        if (!processTerminated || reason == null || reason.isBlank())
            throw new com.sparta.ticket.common.exception.BusinessException(
                    com.sparta.ticket.common.exception.CommonErrorCode.INVALID_REQUEST, "프로세스 종료 확인과 복구 사유가 필요하다");
        var execution = explorer.getJobExecution(id);
        if (execution == null
                || !"pgSettlementJob".equals(execution.getJobInstance().getJobName())
                || !execution.isRunning())
            throw new com.sparta.ticket.common.exception.BusinessException(
                    com.sparta.ticket.common.exception.CommonErrorCode.INVALID_STATUS_TRANSITION,
                    "종료 확인 대상 정산 실행이 아니다");
        var now = LocalDateTime.now(clock);
        var exit = ExitStatus.FAILED.addExitDescription("MANUAL_RECOVERY actor=" + actor + " reason=" + reason);
        for (var step : execution.getStepExecutions())
            if (step.getStatus().isRunning()) {
                step.setStatus(BatchStatus.FAILED);
                step.setEndTime(now);
                step.setExitStatus(exit);
                repository.update(step);
            }
        execution.setStatus(BatchStatus.FAILED);
        execution.setEndTime(now);
        execution.setExitStatus(exit);
        repository.update(execution);
        return execution;
    }
}
