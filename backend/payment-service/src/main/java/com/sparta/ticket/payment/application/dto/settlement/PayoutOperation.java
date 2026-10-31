package com.sparta.ticket.payment.application.dto.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.PayoutOperationQueries;
import java.time.LocalDateTime;
import java.util.List;

/** 지급 운영 명령·조회 입출력(Task 20-10). 멱등 키·계좌 참조·지급대행 거래 ID는 출력하지 않는다. */
public final class PayoutOperation {
    private PayoutOperation() {}

    public enum Result {
        APPLIED,
        DENIED,
        STALE
    }

    /** 운영자가 볼 이유. 지급 상태와 마지막 오류·지급대행 상태로 서버가 계산한다. */
    public enum Attention {
        ACCOUNT_MISSING,
        GATE_BLOCKED,
        READY_UNSCHEDULED,
        READY,
        RESULT_UNKNOWN,
        INQUIRY_ONLY,
        IN_PROGRESS,
        FAILED,
        DONE
    }

    /** 원인을 확인했다는 사유 하나만 둔다. 무엇을 확인했는지는 note에 남긴다. */
    public static final String REASON = "ROOT_CAUSE_FIXED";

    public record Retry(String commandId, Long payoutId, String note, long expectedVersion, int expectedAttempt) {
        public Retry {
            if (commandId == null
                    || !commandId.matches("[A-Za-z0-9_-]{8,80}")
                    || payoutId == null
                    || note == null
                    || note.isBlank()
                    || note.length() > 200
                    || note.chars().anyMatch(Character::isISOControl)
                    || expectedVersion < 0
                    || expectedAttempt < 0) throw new IllegalArgumentException("INVALID_COMMAND");
        }
    }

    public record Outcome(
            String commandId,
            Result result,
            String explanation,
            String status,
            int attempt,
            long version,
            LocalDateTime processedAt) {}

    public record History(
            String commandId,
            String action,
            String actor,
            String reason,
            String note,
            String result,
            String explanation,
            String beforeState,
            String afterState,
            LocalDateTime processedAt) {}

    public record Page<T>(List<T> items, Long nextCursor) {}

    public record Detail(PayoutOperationQueries.View current, List<History> commands) {}
}
