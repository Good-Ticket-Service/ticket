package com.sparta.ticket.reservation.queue.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 대기열(room) 저장소. 구현은 infrastructure의 Redis 어댑터가 맡는다.
 * 저장소에 연결하지 못하면 503(SERVICE_UNAVAILABLE)으로 거절한다(fail-closed).
 */
public interface WaitingRoomRepository {

    /**
     * room을 만든다. scope 중복 확인과 저장을 한 번에 처리해, 같은 scope로 동시에 만들어도 하나만 생긴다.
     * 끝나지 않은(closeAt 전) 다른 room과 scope가 겹치면 409 WAITING_ROOM_SCOPE_CONFLICT다.
     *
     * @param keepUntil 저장한 room을 지울 시각. closeAt 뒤에도 입장한 회원의 정리가 끝날 때까지 남겨 둔다
     */
    WaitingRoom create(List<String> scopes, Instant openAt, Instant closeAt, Instant keepUntil);

    Optional<WaitingRoom> findById(long roomId);

    /**
     * scope로 끝나지 않은 room을 찾는다. 프런트가 회차 화면에서 대기열 유무와 roomId를 알 때 쓴다.
     * scope 연결은 closeAt에 사라지므로 끝난 room은 나오지 않는다. findById는 끝난 room도 돌려준다(409 CLOSED와 404를 나누려고).
     */
    Optional<WaitingRoom> findByScope(String scope);

    /** 끝나지 않은 room 전부(오픈 전 포함). 입장 스케줄러와 Gateway의 보호 대상 목록이 쓴다. 목록 항목은 closeAt에 저절로 사라진다. */
    List<WaitingRoom> findActive();
}
