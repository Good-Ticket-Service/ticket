package com.sparta.ticket.reservation.queue.infrastructure.redis;

/** 대기열 Redis 키 이름. 공연에 묶이지 않게 "queue:{대상}:{ID}:{용도}" 모양으로 짓는다. */
final class QueueKeys {

    /** 대기열이 있는 공연 목록(Set). */
    static final String PERFORMANCES = "queue:performances";

    /** 끝나지 않은 room 목록(Hash). 필드는 roomId, 값은 closeAt(ms)이다. 필드마다 closeAt에 만료되므로(Redis 7.4 HPEXPIREAT) 지우는 코드가 없다. */
    static final String ACTIVE_ROOMS = "queue:active-rooms";

    /** roomId 발급 카운터(INCR). 만료를 걸지 않는다. 지우면 번호가 1부터 다시 시작한다. */
    static final String ROOM_SEQ = "queue:room-seq";

    private QueueKeys() {}

    /** room 설정(Hash). scopes·openAt·closeAt을 담고, closeAt + 입장 유효 시간에 만료된다. */
    static String roomConfig(long roomId) {
        return "queue:room:" + roomId + ":config";
    }

    /** scope를 쓰는 room(String, 값은 roomId). closeAt에 만료되므로 키가 있으면 끝나지 않은 room이 그 scope를 쓰는 중이다. */
    static String scope(String scope) {
        return "queue:scope:" + scope;
    }

    /** 대기 줄(Sorted Set). member는 회원 ID, score는 발급 번호다. */
    static String waiting(long performanceId) {
        return "queue:performance:" + performanceId + ":waiting";
    }

    /** 번호 발급 카운터(INCR). */
    static String seq(long performanceId) {
        return "queue:performance:" + performanceId + ":seq";
    }

    /** 마지막 조회 시각(Sorted Set). member는 회원 ID, score는 Redis 시각(ms)이다. */
    static String seen(long performanceId) {
        return "queue:performance:" + performanceId + ":seen";
    }

    /** 입장 목록(Sorted Set). member는 회원 ID, score는 입장 만료 시각(ms)이다. 입장 인원 상한을 이 크기로 센다. */
    static String active(long performanceId) {
        return "queue:performance:" + performanceId + ":active";
    }

    /** 마지막 입장 시각(ms). 초당 입장 인원을 지키는 토큰 버킷의 기준점이다. */
    static String lastAdmit(long performanceId) {
        return "queue:performance:" + performanceId + ":last-admit";
    }
}
