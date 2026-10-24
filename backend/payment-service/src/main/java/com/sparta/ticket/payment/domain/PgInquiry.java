package com.sparta.ticket.payment.domain;

import java.time.LocalDateTime;

/**
 * PG 조회 결과. "승인 기록 없음"(NotFound)은 승인 요청이 아직 PG로 가는 중일 수 있어 바로 거절로 보지 않는다.
 * 호출한 쪽이 승인 호출 후 유예 시간이 지났는지 보고 판단한다.
 */
public sealed interface PgInquiry {

    record Approved(String transactionId, int amount, LocalDateTime approvedAt) implements PgInquiry {}

    record Canceled(String transactionId, LocalDateTime canceledAt) implements PgInquiry {}

    record NotFound() implements PgInquiry {}

    record Unknown(String reason) implements PgInquiry {}
}
