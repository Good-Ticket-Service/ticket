package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 발급 판단 정보(Redis)를 DB에서 다시 채우고, 남은 수량을 계산한다. 발급·관리자·조회 서비스가 함께 쓴다.
 * 원본 기록은 DB이므로 Redis 정보가 없거나 Redis를 쓸 수 없으면 DB(회원 쿠폰 수)로 대신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueState {

    /**
     * 발급이 끝난 뒤 발급 판단 정보(Redis 키)를 남겨 두는 시간(#323 D1). 이 시간이 지나면 Redis가 키를 지운다.
     * 끝난 직후 늦게 온 요청과, 맞추는 작업이 마지막으로 맞출 시간을 두려고 1일로 둔다. 맞추는 작업의 대상 범위도 같은 기준이다.
     */
    public static final Duration RETENTION = Duration.ofDays(1);

    /** 쿠폰 ID로 고르는 잠금 묶음. 쿠폰마다 잠금 객체를 만들지 않고, 같은 쿠폰은 늘 같은 잠금을 쓴다. */
    private static final int LOCK_STRIPES = 64;

    private final Object[] loadLocks = newLocks();

    private final CouponIssueGate issueGate;
    private final UserCouponRepository userCouponRepository;
    private final Clock clock;

    /**
     * 발급 판단 정보가 없으면 DB에서 채운다. 쿠폰은 있고 삭제되지 않은 것이어야 한다(호출자가 DB에서 읽은 쿠폰).
     * Note: Redis가 비워진 직후 인기 쿠폰에 요청이 몰리면 요청마다 받은 회원 목록(수천~수만 행)을 DB에서 읽게 된다.
     * 이 서버 안에서는 쿠폰마다 한 요청만 DB를 읽고, 기다린 요청은 채워졌는지만 확인한다. 서버가 여러 대면 서버마다 한 번 읽지만,
     * 채우기는 "없을 때만"(load.lua)이라 결과는 같다.
     *
     * @param totalQuantity DB의 총 수량. 관리자 수정 중이면 바꾸기 전 값을 넘긴다
     * @param issueEndAt 발급 종료 시각. 키를 지울 시각을 정한다
     */
    public void ensureLoaded(Long couponId, int totalQuantity, LocalDateTime issueEndAt) {
        synchronized (loadLocks[Math.floorMod(couponId.hashCode(), LOCK_STRIPES)]) {
            // 앞서 기다리던 요청이 이미 채웠으면 DB를 읽지 않는다
            if (issueGate.isLoaded(couponId)) {
                return;
            }
            List<Long> issuedUserIds = userCouponRepository.findUserIdsByCouponId(couponId);
            issueGate.loadIfAbsent(couponId, totalQuantity, false, issuedUserIds, ttl(issueEndAt));
        }
    }

    /**
     * 발급 판단 정보(Redis 키)를 남겨 둘 시간. 지금부터 발급 종료 시각 + 1일(RETENTION)까지다.
     * Note: 지울 시각(EXPIREAT)이 아니라 남은 시간(EXPIRE)으로 건다. 시각은 서버 Clock과 Redis 서버 시계가 다르면 어긋난다
     * (테스트는 Clock을 과거로 정해 두어 시각으로 걸면 키가 바로 지워진다).
     * Note: 이미 끝난 쿠폰에 늦게 온 요청이 키를 다시 채울 때는 1일로 둔다. 0 이하를 걸면 채우자마자 지워져
     * 다시 판단할 때 정보가 없어 503이 된다.
     */
    public Duration ttl(LocalDateTime issueEndAt) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime base = issueEndAt.isAfter(now) ? issueEndAt : now;
        return Duration.between(now, base.plus(RETENTION));
    }

    /** 쿠폰 하나의 남은 수량. */
    public int remainingQuantity(Coupon coupon) {
        return remainingQuantities(List.of(coupon)).get(coupon.getId());
    }

    /**
     * 남은 수량 = DB 총 수량 − 받은 회원 수. 받은 회원 수는 Redis에서 세고, 정보가 없거나 Redis를 쓸 수 없는 쿠폰만 DB에서 센다.
     * Note: p_coupons.issued_quantity는 주기적으로 갱신하는 값이라 늦을 수 있어 쓰지 않는다. 회원 쿠폰 수가 정확한 원본이다.
     */
    public Map<Long, Integer> remainingQuantities(Collection<Coupon> coupons) {
        List<Long> ids = coupons.stream().map(Coupon::getId).toList();
        // 1) Redis에서 센다. 장애면 화면은 계속 보여 주려고 전부 DB로 넘긴다
        Map<Long, Integer> issued = new HashMap<>();
        try {
            issued.putAll(issueGate.issuedCounts(ids));
        } catch (BusinessException e) {
            log.warn("쿠폰 남은 수량을 Redis에서 읽지 못해 DB 회원 쿠폰 수로 셉니다. couponIds={}", ids);
        }
        // 2) Redis에 없는 쿠폰은 DB 회원 쿠폰 수로 센다(받은 회원이 없으면 결과에 없어 0)
        List<Long> missing = ids.stream().filter(id -> !issued.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            Map<Long, Integer> counted = userCouponRepository.countByCouponIds(missing);
            missing.forEach(id -> issued.put(id, counted.getOrDefault(id, 0)));
        }
        // 3) 총 수량에서 뺀다. 맞추기 전 잠깐 어긋나도 음수는 보여 주지 않는다
        Map<Long, Integer> remaining = new HashMap<>();
        for (Coupon coupon : coupons) {
            remaining.put(coupon.getId(), Math.max(0, coupon.getTotalQuantity() - issued.get(coupon.getId())));
        }
        return remaining;
    }

    private static Object[] newLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
