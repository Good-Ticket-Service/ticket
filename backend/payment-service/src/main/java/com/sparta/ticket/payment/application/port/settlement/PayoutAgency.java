package com.sparta.ticket.payment.application.port.settlement;

/**
 * PG 지급대행(settlement-design 7.1절). 결제 PgClient와 분리한다. 지급은 비동기라 요청 응답은 접수일 뿐이고, 결과는 같은 키
 * 조회로 확정한다. 어댑터는 예외를 던지지 않고 결과 타입으로 돌려준다.
 */
public interface PayoutAgency {

    PayoutAgencyResult request(PayoutOrder order);

    PayoutAgencyResult inquire(String idempotencyKey);

    /** 지급 요청 본문. 같은 attempt 동안 키·금액·계좌가 바뀌지 않는다. */
    record PayoutOrder(String idempotencyKey, String sellerRef, String accountRef, String holderMasked, long amount) {}

    /** 지급대행이 알려 준 지급 상태. */
    enum AgencyStatus {
        ACCEPTED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    sealed interface PayoutAgencyResult {

        /** 지급대행이 지급 상태를 알려 줬다. 필드가 빠지거나 해석되지 않으면 null이고, 검증기가 기대값과 맞춰 본다. */
        record Observed(
                AgencyStatus status,
                String payoutId,
                String idempotencyKey,
                String accountRef,
                Long amount,
                String failureCode)
                implements PayoutAgencyResult {}

        /** 조회 404. 지급대행이 그 키를 받은 적이 없다고 권위 있게 답했다. */
        record NotFound() implements PayoutAgencyResult {}

        /** 요청 400. 지급대행이 요청 형식을 거절해 접수하지 않았다. */
        record RequestInvalid(String reason) implements PayoutAgencyResult {}

        /** 요청 422. 같은 키로 다른 본문이 이미 접수됐다. 무엇이 접수됐는지 모르므로 조회로만 확인한다. */
        record KeyConflict(String reason) implements PayoutAgencyResult {}

        /** 2xx인데 본문을 해석할 수 없다. */
        record Malformed(String reason) implements PayoutAgencyResult {}

        /** 전송 실패·응답 유실·429·5xx·인증 오류 등 결과를 모른다. */
        record Unknown(String reason) implements PayoutAgencyResult {}
    }
}
