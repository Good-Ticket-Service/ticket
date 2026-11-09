package com.sparta.ticket.reservation.coupon.domain;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 쿠폰 발급 판단(문지기). 받았는지·남았는지를 DB 행 잠금 대신 빠른 저장소에서 한 번에 판단한다(#300).
 * 원본 기록은 DB(쿠폰, 회원 쿠폰)이고, 여기 값(총 수량, 삭제 표시, 받은 회원)은 모두 DB에서 다시 만들 수 있는 사본이다.
 * 발급 수량은 따로 두지 않고 받은 회원 수로 센다.
 * Note: 정보가 없으면(NOT_LOADED) 판단하지 않고 알린다. 호출자가 DB에서 채운 뒤(loadIfAbsent) 다시 부른다.
 */
public interface CouponIssueGate {

    /**
     * 이 회원을 받은 회원으로 기록할 수 있으면 기록한다. 판단 순서는 오류 우선순위와 같다.
     * 정보 없음 → 이미 받음 → 삭제됨 → 기간 밖 → 소진 → 기록.
     *
     * @param periodOpen DB에서 읽은 쿠폰의 발급 기간 안 여부. 이미 받은 회원에게는 기간보다 "이미 받음"을 먼저 알리려고 판단 안에 넘긴다
     */
    IssueDecision tryIssue(Long couponId, Long userId, boolean periodOpen);

    /** 발급 판단 정보가 있는지. 다시 채우기를 한 요청만 하게 할 때 쓴다. */
    boolean isLoaded(Long couponId);

    /**
     * 정보가 없을 때만 DB 값으로 채운다. 이미 있으면 그사이 기록된 회원을 덮지 않도록 아무것도 하지 않는다.
     *
     * @param ttl 정보를 남겨 둘 시간. 지금부터 발급 종료 + 1일까지다(CouponIssueState.ttl)
     */
    void loadIfAbsent(Long couponId, int totalQuantity, boolean deleted, Collection<Long> issuedUserIds, Duration ttl);

    /**
     * 새 쿠폰의 정보를 만든다. 같은 ID로 남은 이전 값(로컬 테이블 재생성 등)이 있으면 지우고 받은 회원 0명으로 시작한다.
     *
     * @param ttl 정보를 남겨 둘 시간. 지금부터 발급 종료 + 1일까지다(CouponIssueState.ttl)
     */
    void reset(Long couponId, int totalQuantity, Duration ttl);

    /** DB 저장이 실패한 발급의 기록을 되돌린다. 회원 수가 곧 발급 수량이라 회원만 빼면 된다. */
    void cancel(Long couponId, Long userId);

    /** 총 수량을 바꾼다. 받은 회원 수보다 적으면 바꾸지 않는다. 발급과 같은 곳에서 판단해 그사이 발급이 끼어들지 않는다. */
    TotalChange changeTotal(Long couponId, int totalQuantity);

    /** 총 수량이 아직 expected면 restoreTo로 되돌린다. 그사이 다른 변경이 있었으면 그대로 둔다. */
    void restoreTotal(Long couponId, int expected, int restoreTo);

    /** 받은 회원이 없을 때만 삭제 표시를 남긴다. 표시가 있으면 발급은 거절된다. */
    DeleteMark markDeleted(Long couponId);

    /** 삭제 표시를 지운다(DB 삭제가 롤백됐을 때). 정보가 없으면 아무것도 하지 않는다. */
    void unmarkDeleted(Long couponId);

    /** 정보가 있는 쿠폰들의 받은 회원 수. 정보가 없는 쿠폰은 결과에 넣지 않는다. 남은 수량 표시에 쓴다. */
    Map<Long, Integer> issuedCounts(Collection<Long> couponIds);

    // ---- 맞추는 작업(#323): Redis 사본을 DB 원본에 맞춘다 ----

    /** 총 수량·삭제 표시·받은 회원 수를 한 번에 읽는다. 정보가 없으면 비어 있다. */
    Optional<IssueSnapshot> snapshot(Long couponId);

    /** 받은 회원 ID 전부. 큰 회원 키도 Redis를 오래 붙잡지 않도록 나눠 읽는다. */
    Set<Long> issuedUserIds(Long couponId);

    /** DB에만 있는 회원을 추가한다. 정보가 없으면 추가하지 않는다. 추가는 발급을 막는 쪽이라 바로 해도 된다. */
    void addIssued(Long couponId, Collection<Long> userIds);

    /** 총 수량·삭제 표시를 DB 값으로 덮어쓴다. 정보가 없으면 만들지 않는다. 호출자는 쿠폰 행을 잠근 채 부른다. */
    void syncMeta(Long couponId, int totalQuantity, boolean deleted);

    /** 정보 키와 회원 키에 남겨 둘 시간을 다시 건다. 종료 시각이 바뀌었거나 첫 발급이 회원 키를 새로 만들었을 때 맞춘다. */
    void expire(Long couponId, Duration ttl);

    /** 맞추는 작업이 DB와 비교하는 Redis 상태. */
    record IssueSnapshot(int totalQuantity, boolean deleted, int issuedCount) {}

    /** 발급 판단 결과. */
    enum IssueDecision {
        ISSUED,
        NOT_LOADED,
        ALREADY_ISSUED,
        DELETED,
        PERIOD_CLOSED,
        SOLD_OUT
    }

    /** 총 수량 변경 결과. */
    enum TotalChange {
        CHANGED,
        NOT_LOADED,
        BELOW_ISSUED
    }

    /** 삭제 표시 결과. */
    enum DeleteMark {
        MARKED,
        NOT_LOADED,
        ISSUED_EXISTS
    }
}
