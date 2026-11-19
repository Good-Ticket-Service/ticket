package com.sparta.ticket.reservation.seat.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 선점 저장소(#449). REDIS 모드에서 선점을 DB 대신 여기에 두고, 결제하기 때 DB 예약으로 바꾼다.
 *
 * 판정 원칙: 팔린 좌석과 예약의 최종 판정은 DB 좌석 행 잠금이 한다. 이 저장소는 "누가 결제하기를 누를 자격이 있나"만 정한다.
 * 그래서 키가 사라져도 결과는 결제하기 때의 늦은 409이지 이중 판매가 아니다.
 *
 * 회원 키: 선점 중에는 holdId, 예약으로 바뀐 뒤에는 "CONVERTED:예약ID"다. 바뀐 뒤의 값은 회차 시작까지 남겨, 그 회원이 다시 선점할 때만
 * DB를 확인하게 한다. 나머지 선점 시도는 DB를 읽지 않는다.
 *
 * Redis에 연결하지 못하면 모든 메서드가 503 SERVICE_UNAVAILABLE을 던진다. DB 방식으로 자동으로 넘어가지 않는다(피크에 DB 쓰기 몰림이 되살아난다).
 *
 * Note: 시각 판단은 모두 앱 시계(now 인자)로 한다. DB 예약의 만료 판정도 앱 시계라서 같은 기준이어야 한다. Redis 키 만료는 절대 시각이 아니라
 * 상대 시간으로 넘겨, Redis 시계와 앱 시계가 달라도 키 수명이 바뀌지 않게 한다. 남는 차이는 요청이 Redis에 닿기까지의 지연만큼 키가
 * 늦게 사라지는 것뿐이다. 만료 판정은 앱이 하므로 그만큼 좌석이 늦게 풀릴 뿐이다.
 */
public interface SeatHoldStore {

    /**
     * 좌석·회원이 비어 있으면 선점을 기록한다. 결과는 {@link HoldAttempt}의 outcome 설명을 본다.
     *
     * @param heldInDbBy DB에서 이 좌석을 만료 전 HOLD로 잡고 있는 예약(잠금 없이 읽은 값). 없으면 null. 이 회원이 예약으로 바꾼 그
     *     예약이면 MINE_IN_DB, 아니면 TAKEN이다
     * @param now 앱 시계의 현재 시각. 키 만료는 "만료 시각 − now"의 상대 시간으로 넘긴다(아래 Note)
     */
    HoldAttempt hold(StoredHold hold, Long heldInDbBy, Instant now);

    Optional<StoredHold> find(Long scheduleId, String holdId);

    ReleaseAttempt release(Long scheduleId, String holdId, Long userId);

    /**
     * 예약으로 바꾸기를 시작한다. 저장한 만료 시각까지 now 기준으로 minRemain보다 적게 남았으면 EXPIRED다. 이미 바꾸는 중이면 다시
     * STARTED다(앞선 요청이 DB에 커밋했는지 모르므로, DB 트랜잭션의 같은 회원 판정에 맡긴다).
     */
    ConvertStart beginConvert(Long scheduleId, String holdId, Long userId, Instant now, Duration minRemain);

    /** DB 커밋 뒤 부른다. 좌석 키를 지우고(이제 DB가 판정한다) 회원 키를 CONVERTED로 바꿔 userKeyExpiresAt까지 둔다. */
    void finishConvert(StoredHold hold, Long reservationId, Instant userKeyExpiresAt, Instant now);

    /** DB 트랜잭션이 실패했을 때 부른다. drop이면 선점을 지우고(좌석이 이미 DB에서 잡힘 등), 아니면 다시 HELD로 되돌린다. */
    void abortConvert(StoredHold hold, boolean drop);

    /** 지금 Redis로 잡혀 있는(만료 전) 좌석 ID. 좌석 화면 스냅샷이 선택 불가로 덮는다. 예약으로 바꾼 좌석은 빠진다(DB가 HOLD로 보여 준다). */
    Set<Long> heldSeatIds(Long scheduleId, Instant now);

    /** 등급별로 지금 Redis로 잡혀 있는(만료 전) 좌석 수. 잔여석에서 뺀다. 목록에 없는 등급은 0이다. */
    Map<Long, Long> heldCountByGrade(Long scheduleId, Collection<Long> venueSeatGradeIds, Instant now);

    /** 회원 키가 아직 "CONVERTED:reservationId"일 때만 지운다. 그 예약이 이미 끝난 것을 DB로 확인한 뒤 부른다. */
    boolean clearConvertedUser(Long scheduleId, Long userId, Long reservationId);

    enum HoldState {
        HELD,
        CONVERTING,
        CONVERTED
    }

    /**
     * @param outcome CREATED 새로 기록, EXISTING 같은 회원이 같은 좌석을 이미 잡음(holdId는 기존 값), CONVERTED_USER 이 회원은 이미 예약으로
     *     바꾼 적이 있음(reservationId로 DB 확인 필요), TAKEN 좌석을 다른 선점이 잡음, LIMIT 이 회원이 다른 좌석을 잡고 있음,
     *     MINE_IN_DB 이 좌석은 이 회원이 이미 예약으로 바꿨음(reservationId)
     */
    record HoldAttempt(HoldOutcome outcome, String holdId, Long reservationId) {}

    enum HoldOutcome {
        CREATED,
        EXISTING,
        CONVERTED_USER,
        TAKEN,
        LIMIT,
        MINE_IN_DB
    }

    /** @param reservationId outcome이 CONVERTED일 때만 있다. 그 예약은 DB 해제 경로로 푼다 */
    record ReleaseAttempt(ReleaseOutcome outcome, Long reservationId) {}

    enum ReleaseOutcome {
        /** 선점이 없다(이미 해제·만료). 해제는 멱등이라 성공으로 본다. */
        GONE,
        FORBIDDEN,
        /** 예약으로 바꾸는 중이다. */
        BUSY,
        CONVERTED,
        RELEASED
    }

    record ConvertStart(ConvertOutcome outcome, Long reservationId) {}

    enum ConvertOutcome {
        NOT_FOUND,
        FORBIDDEN,
        /** 이미 바꿨다. reservationId가 있다. */
        CONVERTED,
        EXPIRED,
        STARTED
    }
}
