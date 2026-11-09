package com.sparta.ticket.waitingroom.domain;

/** 진입·순번 조회로 알려 주는 내 대기 상태. */
public enum QueueStatus {
    /** 줄에 서 있다. */
    WAITING,
    /** 입장했다. 입장 토큰으로 좌석 조회·선점을 할 수 있다. */
    ENTERED
}
