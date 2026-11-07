package com.sparta.ticket.payment.domain.model.pg;

import java.time.LocalDateTime;

/**
 * PG 승인·취소 호출의 결과. 어댑터가 HTTP 응답을 이 세 가지 중 하나로 바꾼다.
 *
 * Note: 응답 없음(타임아웃)은 실패가 아니라 Unknown이다. 타임아웃을 실패로 보면 실제로 승인된 결제를 놓쳐
 * 이중 결제나 "돈만 빠짐"이 생긴다. sealed로 세 경우를 강제해, 호출한 쪽이 Unknown 처리를 빠뜨리면 컴파일이 실패한다.
 */
public sealed interface PgResult {

    /** 승인 또는 취소 성공. 취소면 transactionId는 PG의 취소 ID다. */
    record Approved(String transactionId, int amount, LocalDateTime at) implements PgResult {}

    /**
     * PG가 처리하지 않았다고 확정한 경우. 연결 단계 실패와 PG 본체의 429도 여기에 넣는다.
     *
     * @param notSentToPg 결제 서비스 자체 동시 호출 상한으로 요청을 PG에 보내지 않았다. PG가 이 요청을 받은 적이 없으므로
     *     승인 거절이어도 최소 대사 대상이 아니다(plan.md 8절 "PG 호출 전 거절은 제외")
     */
    record Rejected(String code, String message, boolean notSentToPg) implements PgResult {
        public Rejected(String code, String message) {
            this(code, message, false);
        }
    }

    /** 처리됐는지 알 수 없는 경우. 복구 잡이 조회로 확정한다. */
    record Unknown(String reason) implements PgResult {}
}
