package com.sparta.ticket.reservation.booking.domain;

/** 확정한 예약이 왜 취소 중이 됐는가. 취소 중이 될 때 한 번 정하고, 환불을 다시 요청해도 같은 사유를 보내는 데 쓴다. */
public enum CancelCause {
    USER,
    SCHEDULE
}
