package com.sparta.ticket.reservation.booking.application.payment;

/**
 * 승인 하나가 가는 길. 결제 중으로 바꾸기 전에 한 번 정하고 바꾸지 않는다.
 * 한 orderId가 두 길로 가면 payment가 같은 주문을 HTTP와 메시지로 함께 받는다.
 */
public enum ConfirmRoute {

    /** 동기 승인으로 설정했다. */
    SYNC,

    /** 승인 요청을 Outbox에 남기고 바로 답한다. */
    MESSAGE,

    /**
     * 메시지 승인으로 설정했지만 승인 요청이 브로커로 나가지 못해 동기로 부른다. 결제 중으로 바꾸는 UPDATE가 동기 승인과 같아서
     * (인증값을 남기고 승인 시한은 비운다) 결과 확인과 접수 제한이 이 예약을 동기 승인과 똑같이 다룬다.
     */
    SYNC_FALLBACK;

    public boolean byMessage() {
        return this == MESSAGE;
    }
}
