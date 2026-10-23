package com.sparta.ticket.common.exception;

import org.springframework.http.HttpStatusCode;

/**
 * enum으로 미리 정의할 수 없는 에러 코드. 하위 서비스가 돌려준 코드를 그대로 전달하거나(FeignErrorDecoder),
 * Spring 표준 예외의 상태를 보존할 때 쓴다.
 */
public record SimpleErrorCode(HttpStatusCode status, String code, String message) implements ErrorCode {
}
