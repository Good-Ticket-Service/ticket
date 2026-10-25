package com.sparta.ticket.fakepg.application.service.fault;

/**
 * 응답을 붙잡았다가 500으로 끝내라는 신호. 지연 승인처럼 PG가 처리를 미룬 경우에 던진다.
 * 멱등 키에는 저장하지 않으므로, 같은 키로 다시 보내면 처리가 이어진다.
 */
public class ResponseHoldException extends RuntimeException {

    public ResponseHoldException() {
        super("응답을 붙잡는다");
    }
}
