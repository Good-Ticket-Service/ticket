package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

/** 회원 쿠폰의 Spring Data 저장소. domain 저장소는 UserCouponRepositoryAdapter가 이 인터페이스로 구현한다. */
public interface UserCouponJpaRepository extends JpaRepository<UserCoupon, Long> {

    boolean existsByUserIdAndCouponId(Long userId, Long couponId);
}
