package com.sparta.ticket.payment.domain.repository.settlement;

import java.time.LocalDateTime;
import java.util.List;

/** 정산 전체 대조(Task 20-11, settlement-design 8.1·8.2절). 대조는 읽기만 하고 고치지 않는다. */
public interface SettlementReconcileRepository {

    /**
     * DB 대조 항목을 한 스냅샷에서 실행한다. 위반이 없어도 모든 항목을 0으로 돌려준다.
     * 항목 하나가 제한 시간을 넘거나 실패하면 그 항목만 미완료로 돌려주고 나머지를 계속한다(#310).
     *
     * @param paymentsSince 이 시각 이후 승인된 결제만 SALE 누락을 본다. 정산 도입 전 결제를 위반으로 세지 않기 위해서다(null이면 전체)
     */
    List<CheckResult> runChecks(LocalDateTime paymentsSince);

    /** 지급대행 대사 대상: doneSince 이후 DONE이 된 지급. */
    List<DonePayout> findDonePayouts(LocalDateTime doneSince, int limit);

    long startRun(LocalDateTime now);

    void finishRun(long runId, Finished finished);

    /**
     * 항목 하나의 결과. 미완료면 위반 수가 없다(null). 0건과 "확인하지 못함"을 구분하기 위해서다.
     *
     * @param sample 확인용 ID 일부(최대 10개, 쉼표 구분). 금액·계좌 원문은 넣지 않는다
     * @param durationMs 항목 실행 시간. DB 밖 항목(지급대행 대사)은 null
     * @param error 미완료 이유(예외 클래스 이름). 완료면 null
     */
    record CheckResult(String check, Long violations, String sample, Long durationMs, String error) {

        public static CheckResult completed(String check, long violations, String sample, Long durationMs) {
            return new CheckResult(check, violations, sample, durationMs, null);
        }

        public static CheckResult incomplete(String check, long durationMs, String error) {
            return new CheckResult(check, null, null, durationMs, error);
        }

        public boolean completed() {
            return violations != null;
        }
    }

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
