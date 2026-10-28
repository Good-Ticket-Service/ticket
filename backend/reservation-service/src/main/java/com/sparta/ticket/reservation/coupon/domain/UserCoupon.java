package com.sparta.ticket.reservation.coupon.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 회원이 받은 쿠폰 한 장. 쿠폰 정의(Coupon)는 관리자가 만들고, 회원이 발급받을 때마다 이 행이 하나 생긴다.
 * 할인액은 발급 시점의 값을 복사해 둔다. 받은 뒤에 쿠폰 정의가 바뀌어도 이미 받은 쿠폰의 조건은 바뀌지 않는다.
 * 명세는 docs/tables.md p_user_coupons다.
 */
@Getter
@Entity
@Table(name = "p_user_coupons")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCoupon extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_coupon_id")
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false)
    private int discountAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserCouponStatus status;

    /**
     * 발급 시각. 만료 시각을 계산한 기준 시각과 같다.
     * Note: created_at은 감사 기능이 시스템 시계로 채워 공용 Clock(Asia/Seoul)과 다를 수 있다. 응답의 issuedAt은 이 컬럼으로 준다.
     */
    @Column(nullable = false)
    private LocalDateTime issuedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 쿠폰을 잡은 예매. RESERVED·USED일 때만 있다. */
    private Long reservationId;

    /** 사용 확정 시각. USED일 때만 있다. */
    private LocalDateTime usedAt;

    private UserCoupon(
            Long userId, Long couponId, int discountAmount, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.couponId = couponId;
        this.discountAmount = discountAmount;
        this.status = UserCouponStatus.AVAILABLE;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 발급. 쿠폰의 할인액을 복사하고, 만료 시각은 발급 시각에 유효 일수를 더한 값이다.
     * 발급 기간·수량 확인은 이 메서드가 아니라 발급 수량의 조건부 UPDATE가 한다. 동시 요청 사이에서 한 번에 판단해야 하기 때문이다.
     */
    public static UserCoupon issue(Long userId, Coupon coupon, LocalDateTime now) {
        // 할인액은 지금 쿠폰 값으로 복사하고, 발급 시각과 만료 시각은 같은 now로 계산한다
        return new UserCoupon(
                userId, coupon.getId(), coupon.getDiscountAmount(), now, now.plusDays(coupon.getValidDays()));
    }
}
