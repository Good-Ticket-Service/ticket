package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 회원 쿠폰의 Spring Data 저장소. domain 저장소는 UserCouponRepositoryAdapter가 이 인터페이스로 구현한다. */
public interface UserCouponJpaRepository extends JpaRepository<UserCoupon, Long> {

    boolean existsByUserIdAndCouponId(Long userId, Long couponId);

    /** 주어진 쿠폰들 가운데 이 회원이 받은 쿠폰의 ID만 읽는다. 쿠폰 수만큼 조회하지 않고 한 번에 가져온다. */
    @Query("SELECT uc.couponId FROM UserCoupon uc WHERE uc.userId = :userId AND uc.couponId IN :couponIds")
    List<Long> findIssuedCouponIds(@Param("userId") Long userId, @Param("couponIds") Collection<Long> couponIds);

    /** 회원의 모든 쿠폰. */
    Page<UserCoupon> findByUserId(Long userId, Pageable pageable);

    /** 저장된 상태가 그대로 보이는 상태(RESERVED·USED)의 쿠폰. */
    Page<UserCoupon> findByUserIdAndStatus(Long userId, UserCouponStatus status, Pageable pageable);

    /** 아직 쓸 수 있는 쿠폰. AVAILABLE이면서 만료 시각 전이다. */
    @Query("""
            SELECT uc FROM UserCoupon uc
            WHERE uc.userId = :userId
              AND uc.status = com.sparta.ticket.reservation.coupon.domain.UserCouponStatus.AVAILABLE
              AND uc.expiresAt > :now
            """)
    Page<UserCoupon> findAvailable(@Param("userId") Long userId, @Param("now") LocalDateTime now, Pageable pageable);

    /** 만료된 쿠폰. 배치가 EXPIRED로 바꾼 것과, 만료 시각이 지났지만 아직 AVAILABLE로 남은 것을 함께 찾는다. */
    @Query("""
            SELECT uc FROM UserCoupon uc
            WHERE uc.userId = :userId
              AND (uc.status = com.sparta.ticket.reservation.coupon.domain.UserCouponStatus.EXPIRED
                   OR (uc.status = com.sparta.ticket.reservation.coupon.domain.UserCouponStatus.AVAILABLE
                       AND uc.expiresAt <= :now))
            """)
    Page<UserCoupon> findExpired(@Param("userId") Long userId, @Param("now") LocalDateTime now, Pageable pageable);
}
