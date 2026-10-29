package com.sparta.ticket.reservation.booking.application.port;

/** booking이 환불을 요청하는 이유. payment는 같은 orderId의 재요청에도 처음 이유를 지킨다. */
public enum RefundReason {
    /** 확정한 예약을 사용자가 취소했다. */
    USER_CANCEL,
    /** 결제는 성공했는데 예약을 확정하지 못했다. 사용자는 돈을 냈지만 표가 없다. */
    CONFIRM_FAILED
}
