package com.sparta.ticket.reservation.seat.domain;

/** 좌석 하나만 보고 내린 선택 가능 판정. 만료된 HOLD는 그 예약이 결제 중인지 알아야 정해지므로 따로 둔다. */
public enum SeatSelectability {
    /** 판매 대상인 AVAILABLE. 바로 선점할 수 있다. */
    SELECTABLE,
    /** 판매 제외, SOLD, 만료 전 HOLD. 선점할 수 없다. */
    NOT_SELECTABLE,
    /** 판매 대상이고 선점 시간이 지난 HOLD. 그 예약이 결제 중이 아니면 선점할 수 있다. */
    EXPIRED_HOLD
}
