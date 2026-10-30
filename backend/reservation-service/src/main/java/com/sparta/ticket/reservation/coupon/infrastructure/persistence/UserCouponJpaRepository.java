package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 회원 쿠폰의 Spring Data 저장소. domain 저장소는 UserCouponRepositoryAdapter가 이 인터페이스로 구현한다. */
public interface UserCouponJpaRepository extends JpaRepository<UserCoupon, Long> {

    boolean existsByUserIdAndCouponId(Long userId, Long couponId);

    /** SELECT ... FOR UPDATE로 회원 쿠폰 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT uc FROM UserCoupon uc WHERE uc.id = :id")
    Optional<UserCoupon> lockById(@Param("id") Long id);

    /** 예매 ID로 회원 쿠폰 행을 잠그고 읽는다. reservation_id는 부분 UNIQUE라 많아야 한 행이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT uc FROM UserCoupon uc WHERE uc.reservationId = :reservationId")
    Optional<UserCoupon> lockByReservationId(@Param("reservationId") Long reservationId);

    /** 이 예매에 주어진 쿠폰 말고 다른 쿠폰이 잡혀 있는지. */
    boolean existsByReservationIdAndIdNot(Long reservationId, Long id);

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

    /**
     * 만료 배치 대상 ID. ix_p_user_coupons_expire(expires_at, WHERE status = 'AVAILABLE')를 타도록 상태를 문자열 그대로 비교한다.
     * 만료 시각이 같으면 ID 순으로 고른다.
     */
    @Query(value = """
            SELECT user_coupon_id FROM p_user_coupons
            WHERE status = 'AVAILABLE'
              AND expires_at <= :now
              AND deleted_at IS NULL
            ORDER BY expires_at, user_coupon_id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findExpirableIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 고른 ID 가운데 아직 AVAILABLE이고 만료 시각이 지난 행만 EXPIRED로 바꾼다.
     * UPDATE가 행을 잠그므로, 예매가 먼저 잡은 쿠폰은 잡은 트랜잭션이 끝난 뒤 WHERE를 다시 보고 건너뛴다.
     * 쿼리로 바로 바꿔 JPA 감사가 돌지 않으므로 updated_at·updated_by를 직접 쓴다.
     * 영속성 컨텍스트에 남은 옛 회원 쿠폰 값을 읽지 않도록 실행 뒤 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE p_user_coupons
            SET status = 'EXPIRED', updated_at = :now, updated_by = :updatedBy
            WHERE user_coupon_id IN (:ids)
              AND status = 'AVAILABLE'
              AND expires_at <= :now
              AND deleted_at IS NULL
            """, nativeQuery = true)
    int expire(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now, @Param("updatedBy") String updatedBy);
}
