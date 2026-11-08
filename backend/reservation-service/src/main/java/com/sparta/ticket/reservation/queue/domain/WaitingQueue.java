package com.sparta.ticket.reservation.queue.domain;

import java.util.Optional;

/**
 * room별 대기 줄. 구현은 infrastructure의 Redis 어댑터가 맡는다.
 * 저장소에 연결하지 못하면 대기열 없이 통과시키지 않고 503(SERVICE_UNAVAILABLE)으로 거절한다(fail-closed).
 */
public interface WaitingQueue {

    /** 줄을 선다. 이미 서 있으면 새로 서지 않고 원래 위치를, 입장해 있으면 줄에 다시 세우지 않고 입장 상태를 돌려준다. */
    QueueState enter(long roomId, long userId);

    /**
     * 지금 위치나 입장 상태를 확인한다. 줄에 있으면 마지막 조회 시각도 남긴다(이탈자 판단용). 줄에도 입장 목록에도 없으면 비어 있다.
     * 조회 시각은 서버가 남기는 관리 기록이라 읽기 요청의 안전성을 해치지 않는다.
     */
    Optional<QueueState> find(long roomId, long userId);

    /** 줄에서 빠진다. 줄에 없어도 오류가 아니다(여러 번 불러도 결과가 같다). 뒤에 선 회원의 순번이 그만큼 당겨진다. */
    void leave(long roomId, long userId);

    /**
     * room 하나의 입장을 처리한다. 만료된 입장자와 떠난 대기자를 빼고, 규칙이 허락하는 만큼 줄 앞에서 입장시킨다.
     * 대기자도 입장자도 없으면 대기열을 닫고 그 room의 키를 지운다.
     */
    AdmissionResult admit(long roomId, AdmissionPolicy policy);
}
