package com.sparta.ticket.payment.domain.repository.settlement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** MASTER 지급 운영 화면용 읽기(Task 20-10). 멱등 키·계좌 참조·지급대행 거래 ID는 읽지 않는다. */
public interface PayoutOperationQueries {

    /** payout ID 오름차순, after 다음부터 limit건. status·attention이 모두 null이면 DONE을 뺀 미완료만. */
    List<View> findPayouts(long after, int limit, String status, String attention);

    Optional<View> findPayout(Long payoutId);

    /** 아직 상계하지 못한 차감이 있는 판매자. sellerId 오름차순, after 다음부터 limit건. */
    List<Receivable> findReceivables(long after, int limit);

    /**
     * @param attention 운영자가 볼 이유. ACCOUNT_MISSING·GATE_BLOCKED·READY_UNSCHEDULED·READY·RESULT_UNKNOWN·INQUIRY_ONLY·
     *     IN_PROGRESS·FAILED·DONE
     */
    record View(
            Long payoutId,
            Long statementId,
            Long sellerId,
            long amount,
            String status,
            String attention,
            int attempt,
            long version,
            String providerStatus,
            boolean inquiryOnly,
            String verificationReason,
            String failureCode,
            String lastError,
            LocalDateTime nextAttemptAt,
            LocalDateTime requestedAt,
            LocalDateTime doneAt,
            String bankCode,
            String holderMasked,
            Long accountVersion) {}

    record Receivable(Long sellerId, long receivable, int openCount, LocalDateTime oldestAt) {}
}
