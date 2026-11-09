package com.sparta.ticket.payment.infrastructure.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;

class SettlementReconcileSchedulerTest {
    private final ReconcileRunner runner = mock(ReconcileRunner.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final JobExplorer explorer = mock(JobExplorer.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private SettlementReconcileScheduler scheduler(String time) {
        var zone = ZoneId.of("Asia/Seoul");
        return new SettlementReconcileScheduler(
                runner,
                repository,
                explorer,
                jdbc,
                Clock.fixed(LocalDateTime.parse(time).atZone(zone).toInstant(), zone));
    }

    @Test
    void startupBeforeThreeChecksPreviousSundayFull() {
        scheduler("2026-11-09T02:59:59").reconcileOnStartup();
        verify(runner).scheduled(LocalDate.of(2026, 11, 8), "FULL");
    }

    @Test
    void threeOClockChecksCurrentMondayDaily() {
        scheduler("2026-11-09T03:00:00").reconcile();
        verify(runner).scheduled(LocalDate.of(2026, 11, 9), "DAILY");
        verify(jdbc)
                .queryForObject(
                        anyString(), eq(Boolean.class), eq("2026-11-09"), eq(Timestamp.valueOf("2026-11-09 03:00:00")));
    }

    @Test
    void healthyFullAlreadyCoversSlot() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), any(Timestamp.class)))
                .thenReturn(true);
        scheduler("2026-11-09T03:01:00").reconcile();
        verify(runner, never()).scheduled(any(), anyString());
    }

    @Test
    void financialFailureIsNotAutomaticallyRetried() {
        var last = failed();
        when(jdbc.queryForList(anyString(), eq(317L)))
                .thenReturn(List.of(Map.of("status", "FAILED", "technical_failure", false)));
        when(explorer.getJobExecutions(last.getJobInstance())).thenReturn(List.of(last));
        scheduler("2026-11-09T04:00:00").reconcile();
        verify(runner, never()).scheduled(any(), anyString());
    }

    @Test
    void technicalFailureUsesSameKeyAndStopsAfterFourAttempts() {
        var last = failed();
        when(jdbc.queryForList(anyString(), eq(317L)))
                .thenReturn(List.of(Map.of("status", "FAILED", "technical_failure", true)));
        when(explorer.getJobExecutions(last.getJobInstance())).thenReturn(List.of(last));
        scheduler("2026-11-09T04:00:00").reconcile();
        verify(runner).scheduled(LocalDate.of(2026, 11, 9), "DAILY");
        when(explorer.getJobExecutions(last.getJobInstance())).thenReturn(List.of(last, last, last, last));
        scheduler("2026-11-09T04:01:00").reconcile();
        org.mockito.Mockito.verifyNoMoreInteractions(runner);
    }

    private JobExecution failed() {
        var execution = mock(JobExecution.class);
        var instance = mock(JobInstance.class);
        when(instance.getInstanceId()).thenReturn(317L);
        when(execution.getJobInstance()).thenReturn(instance);
        when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
        when(execution.getEndTime()).thenReturn(LocalDateTime.of(2026, 11, 9, 3, 0));
        when(repository.getLastJobExecution(eq("reconcileJob"), any())).thenReturn(execution);
        return execution;
    }
}
