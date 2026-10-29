package com.sparta.ticket.reservation.queue.domain;

import java.util.Optional;

/**
 * 공연별 대기 줄. 구현은 infrastructure의 Redis 어댑터가 맡는다.
 * 저장소에 연결하지 못하면 대기열 없이 통과시키지 않고 503(SERVICE_UNAVAILABLE)으로 거절한다(fail-closed).
 */
public interface WaitingQueue {

    /** 줄을 선다. 이미 서 있으면 새로 서지 않고 원래 위치를 돌려준다. */
    QueuePosition enter(long performanceId, long userId);

    /**
     * 지금 위치를 확인한다. 줄에 있으면 마지막 조회 시각도 남긴다(이탈자 판단용). 줄에 없으면 비어 있다.
     * 조회 시각은 서버가 남기는 관리 기록이라 읽기 요청의 안전성을 해치지 않는다.
     */
    Optional<QueuePosition> findPosition(long performanceId, long userId);
}
