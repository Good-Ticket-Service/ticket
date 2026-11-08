package com.sparta.ticket.gateway.queue;

import java.util.List;

/**
 * 서명과 만료를 확인한 입장 토큰의 내용.
 *
 * @param userId 입장한 회원(sub)
 * @param roomId 입장한 대기열(room)
 * @param scopes 이 토큰으로 쓸 수 있는 범위(예: schedule:12). 요청 URL에서 만든 scope가 여기 있어야 통과한다
 */
public record EntryToken(long userId, long roomId, List<String> scopes) {

    public EntryToken {
        scopes = List.copyOf(scopes);
    }

    /** 이 토큰이 그 범위를 허락하는지. */
    public boolean allows(String scope) {
        return scopes.contains(scope);
    }
}
