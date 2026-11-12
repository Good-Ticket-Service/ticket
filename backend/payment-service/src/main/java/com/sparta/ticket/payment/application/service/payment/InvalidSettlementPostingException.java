package com.sparta.ticket.payment.application.service.payment;

/** DLT 판단용 고정 사유다. 원문·역직렬화 예외를 메시지/cause로 전달하지 않는다. */
public class InvalidSettlementPostingException extends RuntimeException {
    public InvalidSettlementPostingException(String reason) {
        super(reason);
    }
}
