package com.sparta.ticket.reservation.coupon.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
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

    /**
     * 받은 쿠폰의 종류(Coupon). 회원 쿠폰 여러 장이 쿠폰 하나를 가리키는 N:1이지만 @ManyToOne 없이 ID만 둔다.
     * Note: 받을 때 할인액·만료 시각을 복사해 쿠폰 정의 없이 동작하고, 발급 락이 몰리는 쿠폰 행을 무심코 불러오지 않게 한다.
     * 쿠폰 정의가 필요하면 한 페이지 단위로 따로 조회한다(N+1 방지). 존재 여부는 DB 외래 키가 지킨다.
     */
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

    /**
     * 회원에게 보여 줄 상태. 만료 배치가 아직 처리하지 않았어도 유효 기간이 지난 AVAILABLE은 EXPIRED로 본다.
     * 저장된 status는 바꾸지 않는다. 만료 시각 정각부터 만료다(만료 시각 = 발급 시각 + 유효 일수).
     * Note: 이 규칙은 내 쿠폰 조회의 상태 필터(UserCouponRepository.findMine)와 같은 경계다. 둘이 다르면 필터와 응답 상태가 어긋난다.
     */
    public UserCouponStatus displayStatus(LocalDateTime now) {
        // 사용 가능 상태인데 만료 시각이 지났으면 만료로 보여 준다
        if (status == UserCouponStatus.AVAILABLE && !now.isBefore(expiresAt)) {
            return UserCouponStatus.EXPIRED;
        }
        // 그 밖에는 저장된 상태 그대로다. RESERVED는 예매가 잡고 있어 기간이 지나도 만료로 보지 않는다(architecture 12절)
        return status;
    }

    /**
     * 예매에 잡는다(AVAILABLE → RESERVED). 결제 준비에서 회원이 이 쿠폰을 골랐을 때 CouponUsage.reserve가 부른다.
     * 본인 쿠폰이 아니거나, AVAILABLE이 아니거나, 유효 기간이 지났으면 COUPON_NOT_AVAILABLE이다.
     * 이미 같은 예매에 잡혀 있으면 아무것도 바꾸지 않는다(멱등). 예매가 같은 요청을 다시 보낼 수 있기 때문이다.
     * Note: 동시에 같은 쿠폰을 잡는 요청은 호출자가 행을 잠그고(FOR UPDATE) 읽어 차례로 이 메서드를 부른다.
     * 뒤에 온 요청은 앞 요청이 커밋한 상태를 보고 판단한다.
     */
    public void reserve(Long userId, Long reservationId, LocalDateTime now) {
        // 1) 남의 쿠폰은 쓸 수 없다. 존재를 드러내지 않으려고 없는 쿠폰과 같은 오류로 답한다
        if (!this.userId.equals(userId)) {
            throw new BusinessException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
        // 2) 이미 이 예매에 잡혀 있으면 앞서 성공한 같은 요청이다. 그대로 둔다
        if (status == UserCouponStatus.RESERVED && reservationId.equals(this.reservationId)) {
            return;
        }
        // 3) 다른 예매가 잡았거나, 이미 썼거나, 만료 처리된 쿠폰은 쓸 수 없다
        if (status != UserCouponStatus.AVAILABLE) {
            throw new BusinessException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
        // 4) 만료 배치 전이라도 만료 시각이 지났으면 쓸 수 없다(displayStatus와 같은 경계: 만료 시각 정각부터 만료)
        if (!now.isBefore(expiresAt)) {
            throw new BusinessException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
        // 5) 이 예매에 잡는다
        this.status = UserCouponStatus.RESERVED;
        this.reservationId = reservationId;
    }

    /**
     * 사용 확정(RESERVED → USED). 결제가 성공해 예매가 확정될 때 CouponUsage.use가 부른다.
     * 잡은 뒤 유효 기간이 지났어도 결제가 확정되면 USED다(architecture 12절). 이미 USED면 아무것도 바꾸지 않는다(멱등).
     */
    public void use(LocalDateTime now) {
        // 이미 사용 처리된 쿠폰이면 앞서 성공한 같은 요청이다
        if (status == UserCouponStatus.USED) {
            return;
        }
        // 예매 ID로 찾은 쿠폰은 RESERVED·USED뿐이다(ck_p_user_coupons_reservation). 그 밖이면 호출 순서가 잘못됐다
        if (status != UserCouponStatus.RESERVED) {
            throw new IllegalStateException("예매에 잡히지 않은 쿠폰은 사용할 수 없습니다. status=" + status);
        }
        this.status = UserCouponStatus.USED;
        this.usedAt = now;
    }

    /**
     * 예매에서 돌려준다(RESERVED·USED → AVAILABLE 또는 EXPIRED). 선점 만료·해제나 예매 취소 확정 때 CouponUsage.release가 부른다.
     * 유효 기간이 남았으면 AVAILABLE, 지났으면 EXPIRED다. 만료 배치는 RESERVED·USED를 건드리지 않으므로 여기서 판정한다.
     * 이미 돌려준 쿠폰이면 아무것도 바꾸지 않는다(멱등).
     */
    public void release(LocalDateTime now) {
        // 예매에 잡혀 있지 않으면 이미 돌려준 것이다
        if (status != UserCouponStatus.RESERVED && status != UserCouponStatus.USED) {
            return;
        }
        // 만료 시각 정각부터 만료다(displayStatus와 같은 경계)
        this.status = now.isBefore(expiresAt) ? UserCouponStatus.AVAILABLE : UserCouponStatus.EXPIRED;
        // DB CHECK가 RESERVED·USED에만 예매 ID를, USED에만 사용 시각을 허용하므로 함께 지운다
        this.reservationId = null;
        this.usedAt = null;
    }
}
