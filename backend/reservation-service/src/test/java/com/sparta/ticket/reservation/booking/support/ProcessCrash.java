package com.sparta.ticket.reservation.booking.support;

/**
 * 요청 처리 도중 서버가 꺼진 것을 흉내 낸다. Error라서 RuntimeException만 잡는 운영 코드의 복구 처리를 거치지 않고,
 * 던진 지점 뒤의 코드는 하나도 실행되지 않는다. 이미 커밋된 DB 상태와 payment가 받은 요청만 남는다.
 */
public class ProcessCrash extends Error {

    public ProcessCrash(String where) {
        super("서버가 꺼졌다: " + where);
    }
}
