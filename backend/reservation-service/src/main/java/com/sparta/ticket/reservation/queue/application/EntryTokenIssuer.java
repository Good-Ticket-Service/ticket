package com.sparta.ticket.reservation.queue.application;

import java.time.Instant;

/**
 * 입장 토큰 발급 기능. application은 서명 라이브러리를 모르고, 구현(JwtEntryTokenIssuer)은 infrastructure에 둔다.
 * Gateway는 Redis에 닿지 않으므로 명부를 조회하지 않고 서명만으로 토큰을 검사한다.
 */
public interface EntryTokenIssuer {

    /** 회원이 공연에 입장했음을 서명한 토큰. 같은 입장이면 몇 번 불러도 같은 토큰이 나온다. */
    String issue(long userId, long performanceId, Instant admittedAt, Instant expiresAt);
}
