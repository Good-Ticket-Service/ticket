package com.sparta.ticket.reservation.seat.infrastructure.redis;

import java.util.List;

/**
 * Redis 선점 키 이름(#449). "seathold:{s회차ID}:용도" 모양이다. 쿠폰(coupon:...)·대기열(queue:...)과 섞이지 않는다.
 * Note: 중괄호 안(해시 태그)이 같으면 Redis 클러스터에서 같은 슬롯에 놓인다. Lua 하나가 쓰는 키가 모두 같은 회차라 클러스터로 바꿔도 그대로 돈다.
 */
final class SeatHoldKeys {

    private SeatHoldKeys() {}

    /** 좌석 키(STRING = holdId). 좌석 하나를 한 선점만 잡게 한다. */
    static String seat(Long scheduleId, Long scheduleSeatId) {
        return prefix(scheduleId) + "seat:" + scheduleSeatId;
    }

    /** 회원 키(STRING = holdId 또는 "CONVERTED:예약ID"). 회원·회차당 1장 제한에 쓴다. */
    static String user(Long scheduleId, Long userId) {
        return prefix(scheduleId) + "user:" + userId;
    }

    /** 선점 키(HASH). 결제하기 때 예약을 만들 재료와 상태를 둔다. */
    static String hold(Long scheduleId, String holdId) {
        return prefix(scheduleId) + "hold:" + holdId;
    }

    /** 선점 좌석 목록(ZSET, member = 좌석 ID, score = 만료 시각 ms). 좌석 화면이 회차마다 한 번에 읽는다. */
    static String held(Long scheduleId) {
        return prefix(scheduleId) + "held";
    }

    /** Lua 스크립트가 받는 KEYS 순서(좌석, 회원, 선점, 목록). */
    static List<String> all(Long scheduleId, Long scheduleSeatId, Long userId, String holdId) {
        return List.of(
                seat(scheduleId, scheduleSeatId), user(scheduleId, userId), hold(scheduleId, holdId), held(scheduleId));
    }

    private static String prefix(Long scheduleId) {
        return "seathold:{s" + scheduleId + "}:";
    }
}
