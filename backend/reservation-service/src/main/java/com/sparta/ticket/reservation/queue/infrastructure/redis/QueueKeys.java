package com.sparta.ticket.reservation.queue.infrastructure.redis;

/** 대기열 Redis 키 이름. 공연에 묶이지 않게 "queue:{대상}:{ID}:{용도}" 모양으로 짓는다. */
final class QueueKeys {

    /** 대기열이 있는 공연 목록(Set). */
    static final String PERFORMANCES = "queue:performances";

    private QueueKeys() {}

    /** 대기 줄(Sorted Set). member는 회원 ID, score는 발급 번호다. */
    static String waiting(long performanceId) {
        return "queue:performance:" + performanceId + ":waiting";
    }

    /** 번호 발급 카운터(INCR). */
    static String seq(long performanceId) {
        return "queue:performance:" + performanceId + ":seq";
    }
}
