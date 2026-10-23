package com.sparta.ticket.common.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반을 알리는 예외. 서비스 코드는 이 예외에 ErrorCode를 담아 던지고,
 * 응답 변환은 GlobalExceptionHandler가 맡는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
