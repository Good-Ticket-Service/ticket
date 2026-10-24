package com.sparta.ticket.payment.domain;

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

    /** PG가 처리하지 않았다고 확정한 경우. 연결 단계 실패와 PG 본체의 429도 여기에 넣는다. */
    record Rejected(String code, String message) implements PgResult {}

    /** 처리됐는지 알 수 없는 경우. 복구 잡이 조회로 확정한다. */
    record Unknown(String reason) implements PgResult {}
}
