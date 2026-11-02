package com.sparta.ticket.gateway.queue;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/** 입장 토큰 검사 오류. 쓰는 곳이 Gateway뿐이라 common이 아니라 Gateway에 둔다. 목록은 docs/api.md 좌석 절의 오류다. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum QueueTokenErrorCode implements ErrorCode {
    QUEUE_TOKEN_INVALID(HttpStatus.FORBIDDEN, "입장 토큰이 없거나 유효하지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
