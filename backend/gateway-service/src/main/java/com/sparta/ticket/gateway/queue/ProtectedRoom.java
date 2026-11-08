package com.sparta.ticket.gateway.queue;

import java.time.Instant;
import java.util.List;

/**
 * 대기열 room 하나가 보호하는 scope와 보호가 끝나는 시각. ProtectedScopes가 이 목록으로 검사 대상을 정한다.
 * 받아 오는 방법(WaitingRoomClient)과 떼어 두어 queue가 클라이언트에 기대지 않게 한다.
 */
public record ProtectedRoom(List<String> scopes, Instant closeAt) {

    public ProtectedRoom {
        scopes = List.copyOf(scopes);
    }
}
