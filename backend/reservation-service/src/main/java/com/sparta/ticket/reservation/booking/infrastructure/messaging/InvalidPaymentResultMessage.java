package com.sparta.ticket.reservation.booking.infrastructure.messaging;

/** 다시 읽어도 해석할 수 없는 결제 결과 메시지다. 재시도하지 않고 DLT로 보낸다. */
class InvalidPaymentResultMessage extends RuntimeException {

    InvalidPaymentResultMessage(String message, Throwable cause) {
        super(message, cause);
    }

    InvalidPaymentResultMessage(String message) {
        super(message);
    }
}
