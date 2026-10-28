package com.sparta.ticket.performance.domain.performance;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * 공연 오류 코드. BusinessException에 담아 던지면 공통 예외 처리가 HTTP 상태와 code·message로 응답한다.
 * 쿼리 형식 오류(400 VALIDATION_ERROR)는 CommonErrorCode를 쓴다. 목록은 .local/performance/api.md 5.11이다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum PerformanceErrorCode implements ErrorCode {
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "시작 날짜는 종료 날짜보다 늦을 수 없습니다."),
    PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "공연을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
