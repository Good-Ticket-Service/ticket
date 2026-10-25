package com.sparta.ticket.fakepg.domain.exception;

import lombok.Getter;

/** PG 오류 응답으로 바뀌는 예외. 컨트롤러 밖으로 나가면 토스 형식의 오류 본문이 된다. */
@Getter
public class PgException extends RuntimeException {

    private final PgErrorCode errorCode;

    public PgException(PgErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public PgException(PgErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
