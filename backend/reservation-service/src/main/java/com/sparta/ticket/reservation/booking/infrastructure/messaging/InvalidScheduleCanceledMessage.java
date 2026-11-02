package com.sparta.ticket.reservation.booking.infrastructure.messaging;

/** 다시 읽어도 해석할 수 없는 메시지다. 재시도하지 않고 DLT로 보낸다. */
class InvalidScheduleCanceledMessage extends RuntimeException {

    InvalidScheduleCanceledMessage(String message, Throwable cause) {
        super(message, cause);
    }

    InvalidScheduleCanceledMessage(String message) {
        super(message);
    }
}
