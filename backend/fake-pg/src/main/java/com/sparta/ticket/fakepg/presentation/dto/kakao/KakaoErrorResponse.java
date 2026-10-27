package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import org.springframework.http.ResponseEntity;

/**
 * 카카오 형식 오류 본문 {@code {"error_code", "error_message"}}.
 *
 * Note: 카카오는 업무 오류를 대부분 400과 음수 코드로 돌려준다. 아래 코드값은 fake PG가 정한 대응이며 실제 카카오 코드와 다를 수
 * 있다. payment의 카카오 어댑터는 docs/api.md의 Fake PG 계약의 대응표를 기준으로 분류하고, 샌드박스 검증 때 맞춘다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoErrorResponse(int errorCode, String errorMessage) {

    public static ResponseEntity<Object> of(PgErrorCode code, String message) {
        return ResponseEntity.status(status(code)).body(new KakaoErrorResponse(errorCode(code), message));
    }

    private static int status(PgErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED_KEY -> 401;
            case TOO_MANY_REQUESTS -> 429;
            case FAILED_INTERNAL_SYSTEM_PROCESSING -> 500;
            default -> 400;
        };
    }

    private static int errorCode(PgErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, IDEMPOTENT_REQUEST_PROCESSING, INVALID_IDEMPOTENCY_KEY -> -2;
            case UNAUTHORIZED_KEY -> -401;
            case NOT_AUTHENTICATED_PAYMENT -> -701;
            case ALREADY_PROCESSED_PAYMENT -> -702;
            case NOT_FOUND_PAYMENT_SESSION -> -703;
            case NOT_FOUND_PAYMENT -> -704;
            case ALREADY_CANCELED_PAYMENT -> -721;
            case NOT_CANCELABLE_PAYMENT -> -722;
            case REJECT_CARD_PAYMENT -> -780;
            case TOO_MANY_REQUESTS -> -429;
            case FAILED_INTERNAL_SYSTEM_PROCESSING -> -500;
        };
    }
}
