package com.sparta.ticket.reservation.booking.application.facade;

/** {@link BookingFacade#release}의 결과. seat는 이 값으로 응답과 좌석 반환을 정한다. */
public enum ReleaseResult {
    /** HOLD를 RELEASED로 바꿨다. 좌석을 돌려준다. 204 */
    RELEASED,
    /** 선점 시간이 이미 지나 EXPIRED로 정리했다. 좌석을 돌려준다. 204 */
    EXPIRED,
    /** 이미 RELEASED나 EXPIRED다. 아무것도 바꾸지 않았다. 좌석이 이 예약에 남아 있으면 돌려준다. 204 */
    ALREADY_CLOSED,
    /** 결제 중·확정·취소 중·취소된 예약이라 해제할 수 없다. 아무것도 바꾸지 않았다. 409 INVALID_STATUS_TRANSITION */
    NOT_RELEASABLE
}
