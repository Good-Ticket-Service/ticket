package com.sparta.ticket.payment.application.port.settlement;

import java.util.Map;
import java.util.UUID;

public interface ProjectionOperations {
    UUID create(UUID commandId, String mode, String actor, String reason);

    Map<String, Object> progress(UUID buildId);

    record Result(UUID commandId, UUID buildId, String status, long generation, boolean verified) {}

    Result control(UUID commandId, UUID buildId, String action, long expectedGeneration, String actor, String reason);
}
