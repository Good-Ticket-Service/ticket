package com.sparta.ticket.performance.domain.schedule;

/**
 * 판매 검증의 거절 사유. 오류 코드가 아니라 판매 검증 200 응답의 reasonCode 값이다.
 * 좌석 서비스가 이 이름을 외부 선점 API의 오류 code로 그대로 쓰므로 이름을 바꾸지 않는다. 목록은 docs/api.md 「판매 거절 사유」다.
 * Note: 공개 조회의 {@link SalesStatus}(화면용 5개 값)와 다르다. 판매 중지·좌석 미준비, 공연·회차 취소를 따로 구분한다.
 */
public enum SaleRejectReason {
    PERFORMANCE_NOT_PUBLISHED,
    PERFORMANCE_CANCELED,
    SCHEDULE_CANCELED,
    SALES_SUSPENDED,
    SEAT_SETUP_NOT_READY,
    BEFORE_OPEN,
    SALES_CLOSED,
    SEAT_NOT_SELLABLE,
    SEAT_CONFIG_VERSION_MISMATCH
}
