package com.sparta.ticket.reservation.queue.domain;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * 대기열 오류 코드. Redis에 닿지 못할 때는 CommonErrorCode.SERVICE_UNAVAILABLE을 쓴다.
 * 목록은 docs/api.md 대기열 절의 오류 표다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum QueueErrorCode implements ErrorCode {
    QUEUE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "대기열을 사용하지 않는 중입니다."),
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "대기열에 서 있지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
