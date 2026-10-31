package com.sparta.ticket.payment.domain.repository.settlement;

import java.time.LocalDateTime;
import java.util.List;

/** 정산 전체 대조(Task 20-11, settlement-design 8.1·8.2절). 대조는 읽기만 하고 고치지 않는다. */
public interface SettlementReconcileRepository {

    /**
     * DB 대조 항목을 한 스냅샷에서 실행한다. 위반이 없어도 모든 항목을 0으로 돌려준다.
     *
     * @param paymentsSince 이 시각 이후 승인된 결제만 SALE 누락을 본다. 정산 도입 전 결제를 위반으로 세지 않기 위해서다(null이면 전체)
     */
    List<CheckResult> runChecks(LocalDateTime paymentsSince);

    /** 지급대행 대사 대상: doneSince 이후 DONE이 된 지급. */
    List<DonePayout> findDonePayouts(LocalDateTime doneSince, int limit);

    long startRun(LocalDateTime now);

    void finishRun(long runId, Finished finished);

    /**
     * @param sample 확인용 ID 일부(최대 10개, 쉼표 구분). 금액·계좌 원문은 넣지 않는다
     */
    record CheckResult(String check, long violations, String sample) {}

    record DonePayout(Long payoutId, String idempotencyKey, long amount, String accountRef, String externalPayoutId) {}

    record Finished(
            String status,
            LocalDateTime finishedAt,
            Long violations,
            Integer providerChecked,
            Integer providerUnreachable,
            String error,
            List<CheckResult> results) {}
}
