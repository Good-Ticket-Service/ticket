package com.sparta.ticket.reservation.coupon.domain;

import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface CouponRepository {

    Coupon save(Coupon coupon);

    /** 논리 삭제된 쿠폰은 찾지 않는다(Coupon의 @SQLRestriction). */
    Optional<Coupon> findById(Long id);

    /**
     * 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 관리자의 수정·발급 중단처럼 발급과 같은 행을 바꾸기 전에 쓴다.
     * 발급의 조건부 UPDATE는 이 트랜잭션이 끝날 때까지 기다리므로, 잠근 뒤 읽은 발급 수량으로 판단해도 된다.
     */
    Optional<Coupon> lockById(Long id);
}
