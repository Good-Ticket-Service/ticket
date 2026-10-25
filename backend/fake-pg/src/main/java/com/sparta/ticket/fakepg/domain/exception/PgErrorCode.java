package com.sparta.ticket.fakepg.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 토스페이먼츠 오류 코드 중 fake PG가 내는 값. 응답 본문은 {@code {"code": ..., "message": ...}} 형식이다.
 *
 * Note: {@code stored}는 멱등 키에 저장해 재요청 때 그대로 재현할 응답인지다. PG가 처리를 끝내고 낸 업무 응답만 저장하고,
 * 처리 전에 막힌 응답(5xx, 429, 동시 처리 중)은 저장하지 않는다. 그래야 같은 키로 다시 보내면 처리가 이어진다.
 */
@Getter
@RequiredArgsConstructor
public enum PgErrorCode {
    INVALID_REQUEST(400, true, "잘못된 요청입니다."),
    ALREADY_PROCESSED_PAYMENT(400, true, "이미 처리된 결제입니다."),
    ALREADY_CANCELED_PAYMENT(400, true, "이미 취소된 결제입니다."),
    UNAUTHORIZED_KEY(401, false, "인증되지 않은 시크릿 키입니다."),
    REJECT_CARD_PAYMENT(403, true, "한도초과 혹은 잔액부족으로 결제에 실패했습니다."),
    NOT_CANCELABLE_PAYMENT(403, true, "취소할 수 없는 결제입니다."),
    NOT_FOUND_PAYMENT(404, true, "존재하지 않는 결제 정보입니다."),
    NOT_FOUND_PAYMENT_SESSION(404, true, "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다."),
    IDEMPOTENT_REQUEST_PROCESSING(409, false, "같은 멱등 키의 요청을 처리하고 있습니다."),
    INVALID_IDEMPOTENCY_KEY(422, false, "같은 멱등 키로 다른 내용의 요청을 보낼 수 없습니다."),
    TOO_MANY_REQUESTS(429, false, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    FAILED_INTERNAL_SYSTEM_PROCESSING(500, false, "내부 시스템 처리 작업이 실패했습니다.");

    private final int status;
    private final boolean stored;
    private final String message;
}
