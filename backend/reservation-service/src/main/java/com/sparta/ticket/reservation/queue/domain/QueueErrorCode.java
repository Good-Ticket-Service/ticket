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
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "대기열에 서 있지 않습니다."),
    WAITING_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "대기열이 없습니다."),
    WAITING_ROOM_NOT_OPEN(HttpStatus.CONFLICT, "대기열이 아직 열리지 않았습니다."),
    WAITING_ROOM_CLOSED(HttpStatus.CONFLICT, "대기열이 끝났습니다."),
    WAITING_ROOM_SCOPE_CONFLICT(HttpStatus.CONFLICT, "끝나지 않은 다른 대기열과 보호 범위가 겹칩니다."),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "대기열 시각이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
