package com.sparta.ticket.payment.application.port.settlement;

import com.sparta.ticket.payment.application.dto.payment.SettlementPostingCommand;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 처리 성공 receipt는 기존 job/집계 receipt를 쓰고 격리·운영 명령만 별도 보존한다. */
public interface FinancialConsumerRecovery {
    record Source(String stage, String group, String topic, int partition, long offset, String payloadHash) {}

    record Incident(UUID id, String status, Long jobId) {}

    record Outcome(UUID commandId, String status, String explanation) {}

    Incident quarantine(Source source, String reason, SettlementPostingCommand trusted);

    void deadLetterConfirmed(UUID incidentId);

    void validateReplay(UUID commandId, long jobId);

    void replayApplied(UUID commandId, long jobId);

    Incident quarantineProjection(Source source, UUID build, String factKey, String reason, UUID replayCommandId);

    void validateProjectionReplay(UUID commandId, UUID build, String factKey);

    void projectionFactsApplied(UUID build, java.util.Collection<String> factKeys);

    List<Map<String, Object>> list(long after, int limit, String status);

    Map<String, Object> command(UUID commandId);

    Outcome request(UUID commandId, UUID incidentId, String action, String actor, String reason);
}
