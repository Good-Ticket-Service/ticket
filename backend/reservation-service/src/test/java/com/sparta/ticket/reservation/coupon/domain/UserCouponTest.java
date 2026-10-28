package com.sparta.ticket.reservation.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserCouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    /** 발급 중(ISSUING)이고 유효 일수가 7일인 쿠폰. */
    private static Coupon issuing() {
        return Coupon.create("오픈 기념 5,000원 할인", 5_000, 1_000, NOW.minusDays(1), NOW.plusDays(7), 7, NOW);
    }

    @Test
    @DisplayName("발급하면 AVAILABLE이고, 쿠폰의 할인액을 복사하며 예매·사용 정보는 없다")
    void issue() {
        UserCoupon userCoupon = UserCoupon.issue(1L, issuing(), NOW);

        assertThat(userCoupon.getUserId()).isEqualTo(1L);
        assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
        assertThat(userCoupon.getDiscountAmount()).isEqualTo(5_000);
        assertThat(userCoupon.getReservationId()).isNull();
        assertThat(userCoupon.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("발급 시각은 현재 시각이고, 만료 시각은 발급 시각에 유효 일수를 더한 값이다")
    void expiresAtIsIssuedAtPlusValidDays() {
        UserCoupon userCoupon = UserCoupon.issue(1L, issuing(), NOW);

        assertThat(userCoupon.getIssuedAt()).isEqualTo(NOW);
        assertThat(userCoupon.getExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    @DisplayName("AVAILABLE은 만료 시각 1초 전까지 AVAILABLE로, 만료 시각 정각부터 EXPIRED로 보인다")
    void displayStatusAtExpiry() {
        UserCoupon userCoupon = UserCoupon.issue(1L, issuing(), NOW);
        LocalDateTime expiresAt = userCoupon.getExpiresAt();

        assertThat(userCoupon.displayStatus(expiresAt.minusSeconds(1))).isEqualTo(UserCouponStatus.AVAILABLE);
        assertThat(userCoupon.displayStatus(expiresAt)).isEqualTo(UserCouponStatus.EXPIRED);
        // 보여 주는 상태만 바뀌고 저장된 상태는 그대로다
        assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
    }

    @Test
    @DisplayName("RESERVED·USED는 만료 시각이 지나도 저장된 상태 그대로 보인다")
    void displayStatusKeepsReservedAndUsed() {
        UserCoupon userCoupon = UserCoupon.issue(1L, issuing(), NOW);
        LocalDateTime afterExpiry = userCoupon.getExpiresAt().plusDays(1);

        // RESERVED·USED 전이는 예매 연동(CouponUsage)이 맡아 아직 메서드가 없으므로 필드를 직접 바꾼다
        for (UserCouponStatus status : new UserCouponStatus[] {UserCouponStatus.RESERVED, UserCouponStatus.USED}) {
            ReflectionTestUtils.setField(userCoupon, "status", status);
            assertThat(userCoupon.displayStatus(afterExpiry)).isEqualTo(status);
        }
    }
}
