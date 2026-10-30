package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutAgencyResult.Observed;
import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.PayoutFailure;
import com.sparta.ticket.payment.domain.model.settlement.PayoutProviderStatus;

/**
 * 지급대행 응답을 실행권 스냅샷과 맞춘다(settlement-design 7.2절). HTTP 2xx나 상태 값만으로 확정하지 않고 키·금액·계좌·거래 ID가
 * 모두 맞을 때만 결과로 인정한다. 응답 값으로 스냅샷을 바꾸지 않는다.
 * Note: 거래 ID는 처음 받은 값을 결합하고, 그 뒤 응답은 같은 ID인지 비교한다. 처음 응답에 ID가 없는 것도 불일치다.
 */
final class PayoutResultVerifier {

    private PayoutResultVerifier() {}

    sealed interface Verdict {

        record Completed(String externalPayoutId) implements Verdict {}

        record Failed(PayoutFailure code, String externalPayoutId) implements Verdict {}

        record InProgress(PayoutProviderStatus status, String externalPayoutId) implements Verdict {}

        record Mismatch(String reason) implements Verdict {}
    }

    static Verdict verify(Payout payout, Observed observed) {
        if (observed.status() == null) return new Verdict.Mismatch("STATUS_MISSING");
        if (!payout.getIdempotencyKey().equals(observed.idempotencyKey())) return new Verdict.Mismatch("KEY_MISMATCH");
        if (observed.amount() == null || observed.amount() != payout.getAmount())
            return new Verdict.Mismatch("AMOUNT_MISMATCH");
        if (!payout.getAccountRef().equals(observed.accountRef())) return new Verdict.Mismatch("ACCOUNT_MISMATCH");
        String payoutId = observed.payoutId();
        if (payoutId == null || payoutId.isBlank()) return new Verdict.Mismatch("PAYOUT_ID_MISSING");
        if (payout.getExternalPayoutId() != null
                && !payout.getExternalPayoutId().equals(payoutId)) return new Verdict.Mismatch("PAYOUT_ID_MISMATCH");
        return switch (observed.status()) {
            case ACCEPTED -> new Verdict.InProgress(PayoutProviderStatus.ACCEPTED, payoutId);
            case PROCESSING -> new Verdict.InProgress(PayoutProviderStatus.PROCESSING, payoutId);
            case COMPLETED ->
                observed.failureCode() == null
                        ? new Verdict.Completed(payoutId)
                        : new Verdict.Mismatch("COMPLETED_WITH_FAILURE");
            case FAILED -> failure(observed.failureCode(), payoutId);
        };
    }

    /** 지급대행이 알려 줄 수 있는 실패 코드만 받는다. REQUEST_INVALID는 400 응답에서만 정한다. */
    private static Verdict failure(String code, String payoutId) {
        if (PayoutFailure.ACCOUNT_INVALID.name().equals(code))
            return new Verdict.Failed(PayoutFailure.ACCOUNT_INVALID, payoutId);
        if (PayoutFailure.REJECTED.name().equals(code)) return new Verdict.Failed(PayoutFailure.REJECTED, payoutId);
        return new Verdict.Mismatch("FAILURE_CODE_UNKNOWN");
    }
}
