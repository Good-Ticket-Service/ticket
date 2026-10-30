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
    PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "공연을 찾을 수 없습니다."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."),
    VENUE_NOT_FOUND(HttpStatus.NOT_FOUND, "공연장을 찾을 수 없습니다."),
    VENUE_GRADE_MISMATCH(HttpStatus.BAD_REQUEST, "이 공연장의 좌석 등급이 아닙니다."),
    GRADE_PRICE_MISSING(HttpStatus.BAD_REQUEST, "판매 대상 좌석이 쓰는 등급의 가격이 없습니다."),
    DUPLICATE_VENUE_GRADE(HttpStatus.CONFLICT, "같은 이름의 좌석 등급이 이미 있습니다."),
    DUPLICATE_VENUE_SEAT(HttpStatus.CONFLICT, "같은 구역·열·번호의 좌석이 이미 있습니다."),
    VENUE_LAYOUT_IN_USE(HttpStatus.CONFLICT, "공연이 사용 중인 공연장은 좌석 등급·좌석을 바꿀 수 없습니다."),
    PERFORMANCE_CONFIGURATION_LOCKED(HttpStatus.CONFLICT, "공개된 공연은 가격·판매 제외 같은 구성을 바꿀 수 없습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 요청이 먼저 변경했습니다. 최신 정보를 다시 조회해 주세요."),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "If-Match 헤더가 필요합니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
