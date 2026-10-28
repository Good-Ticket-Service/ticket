package com.sparta.ticket.reservation.queue.domain;

/**
 * 공연별 대기 줄. 구현은 infrastructure의 Redis 어댑터가 맡는다.
 * 저장소에 연결하지 못하면 대기열 없이 통과시키지 않고 503(SERVICE_UNAVAILABLE)으로 거절한다(fail-closed).
 */
public interface WaitingQueue {

    /** 줄을 선다. 이미 서 있으면 새로 서지 않고 원래 위치를 돌려준다. */
    QueuePosition enter(long performanceId, long userId);
}
