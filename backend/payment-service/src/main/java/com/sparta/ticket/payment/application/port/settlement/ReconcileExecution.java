package com.sparta.ticket.payment.application.port.settlement;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public interface ReconcileExecution {
    record Execution(long executionId, String status) {}

    Execution run(LocalDate businessDate, UUID commandId, String reason, String actor);

    Execution retry(long executionId);

    Execution recover(long executionId, String reason, String actor, boolean processTerminated);

    Map<String, Object> progress(long executionId);
}
