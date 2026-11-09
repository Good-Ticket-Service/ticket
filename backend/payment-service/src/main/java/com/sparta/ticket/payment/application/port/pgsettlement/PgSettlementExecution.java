package com.sparta.ticket.payment.application.port.pgsettlement;

import java.time.LocalDate;

public interface PgSettlementExecution {
    record Execution(long executionId, String status) {}

    record StepProgress(String name, String status, long readCount, long writeCount) {}

    record Progress(
            long executionId,
            String provider,
            String settlementDate,
            String status,
            java.util.List<StepProgress> steps) {}

    Execution run(String provider, LocalDate date);

    Progress progress(long executionId);

    Execution recover(long executionId, String reason, String actor, boolean processTerminated);
}
