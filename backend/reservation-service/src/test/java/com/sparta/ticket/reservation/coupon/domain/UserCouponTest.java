package com.sparta.ticket.reservation.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserCouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 7L;

    /** 발급 중(ISSUING)이고 유효 일수가 7일인 쿠폰. */
    private static Coupon issuing() {
        return Coupon.create("오픈 기념 5,000원 할인", 5_000, 1_000, NOW.minusDays(1), NOW.plusDays(7), 7, NOW);
    }

    /** 회원 1이 NOW에 받은 쿠폰. 만료 시각은 NOW + 7일이다. */
    private static UserCoupon available() {
        return UserCoupon.issue(USER_ID, issuing(), NOW);
    }

    /** 예외가 COUPON_NOT_AVAILABLE인지 확인한다. */
    private static void assertNotAvailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_AVAILABLE);
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
        UserCoupon userCoupon = available();
        LocalDateTime afterExpiry = userCoupon.getExpiresAt().plusDays(1);

        userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);
        assertThat(userCoupon.displayStatus(afterExpiry)).isEqualTo(UserCouponStatus.RESERVED);

        userCoupon.use(NOW);
        assertThat(userCoupon.displayStatus(afterExpiry)).isEqualTo(UserCouponStatus.USED);
    }

    @Nested
    @DisplayName("reserve: 예매에 잡기")
    class Reserve {

        @Test
        @DisplayName("AVAILABLE이고 본인 쿠폰이며 유효 기간 안이면 RESERVED가 되고 예매 ID가 기록된다")
        void reserves() {
            UserCoupon userCoupon = available();

            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW.plusDays(1));

            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.RESERVED);
            assertThat(userCoupon.getReservationId()).isEqualTo(RESERVATION_ID);
            assertThat(userCoupon.getUsedAt()).isNull();
        }

        @Test
        @DisplayName("같은 예매로 다시 잡으면 예외 없이 그대로다(멱등)")
        void idempotent() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);

            // 만료 시각이 지난 뒤의 재요청이어도 이미 잡혀 있으면 앞서 성공한 요청으로 본다
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW.plusDays(30));

            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.RESERVED);
            assertThat(userCoupon.getReservationId()).isEqualTo(RESERVATION_ID);
        }

        @Test
        @DisplayName("남의 쿠폰이면 COUPON_NOT_AVAILABLE이고 상태는 그대로다")
        void othersCoupon() {
            UserCoupon userCoupon = available();

            assertNotAvailable(() -> userCoupon.reserve(2L, RESERVATION_ID, NOW));
            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
            assertThat(userCoupon.getReservationId()).isNull();
        }

        @Test
        @DisplayName("다른 예매가 잡았거나 이미 쓴 쿠폰은 COUPON_NOT_AVAILABLE이다")
        void notAvailableStatus() {
            UserCoupon reserved = available();
            reserved.reserve(USER_ID, RESERVATION_ID, NOW);
            UserCoupon used = available();
            used.reserve(USER_ID, RESERVATION_ID, NOW);
            used.use(NOW);

            assertNotAvailable(() -> reserved.reserve(USER_ID, 8L, NOW));
            // 같은 예매라도 이미 쓴 쿠폰은 다시 잡을 수 없다
            assertNotAvailable(() -> used.reserve(USER_ID, RESERVATION_ID, NOW));
            assertThat(reserved.getReservationId()).isEqualTo(RESERVATION_ID);
        }

        @Test
        @DisplayName("만료 시각 1초 전에는 잡히고, 만료 시각 정각부터는 COUPON_NOT_AVAILABLE이다")
        void expiryBoundary() {
            UserCoupon beforeExpiry = available();
            UserCoupon atExpiry = available();
            LocalDateTime expiresAt = beforeExpiry.getExpiresAt();

            beforeExpiry.reserve(USER_ID, RESERVATION_ID, expiresAt.minusSeconds(1));

            assertThat(beforeExpiry.getStatus()).isEqualTo(UserCouponStatus.RESERVED);
            assertNotAvailable(() -> atExpiry.reserve(USER_ID, RESERVATION_ID, expiresAt));
        }
    }

    @Nested
    @DisplayName("use: 사용 확정")
    class Use {

        @Test
        @DisplayName("RESERVED면 USED가 되고 사용 시각이 기록되며 예매 ID는 남는다")
        void uses() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);

            userCoupon.use(NOW.plusHours(1));

            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.USED);
            assertThat(userCoupon.getUsedAt()).isEqualTo(NOW.plusHours(1));
            assertThat(userCoupon.getReservationId()).isEqualTo(RESERVATION_ID);
        }

        @Test
        @DisplayName("잡은 뒤 유효 기간이 지났어도 결제가 확정되면 USED다")
        void usesAfterExpiry() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);

            userCoupon.use(userCoupon.getExpiresAt().plusDays(1));

            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.USED);
        }

        @Test
        @DisplayName("이미 USED면 사용 시각을 바꾸지 않는다(멱등)")
        void idempotent() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);
            userCoupon.use(NOW);

            userCoupon.use(NOW.plusHours(3));

            assertThat(userCoupon.getUsedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("예매에 잡히지 않은 쿠폰을 쓰려 하면 IllegalStateException이다")
        void notReserved() {
            UserCoupon userCoupon = available();

            assertThatThrownBy(() -> userCoupon.use(NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("release: 예매에서 돌려주기")
    class Release {

        @Test
        @DisplayName("RESERVED를 유효 기간 안에 돌려주면 AVAILABLE이고 예매 ID가 지워진다")
        void releasesReserved() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);

            userCoupon.release(NOW.plusDays(1));

            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
            assertThat(userCoupon.getReservationId()).isNull();
            assertThat(userCoupon.getUsedAt()).isNull();
        }

        @Test
        @DisplayName("USED를 돌려주면(예매 취소) AVAILABLE이고 예매 ID와 사용 시각이 지워진다")
        void releasesUsed() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);
            userCoupon.use(NOW);

            userCoupon.release(NOW.plusDays(1));

            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
            assertThat(userCoupon.getReservationId()).isNull();
            assertThat(userCoupon.getUsedAt()).isNull();
        }

        @Test
        @DisplayName("만료 시각 정각 이후에 돌려주면 EXPIRED다")
        void releasesAsExpired() {
            UserCoupon reserved = available();
            reserved.reserve(USER_ID, RESERVATION_ID, NOW);
            UserCoupon used = available();
            used.reserve(USER_ID, 8L, NOW);
            used.use(NOW);
            LocalDateTime expiresAt = reserved.getExpiresAt();

            reserved.release(expiresAt);
            used.release(expiresAt.plusDays(1));

            assertThat(reserved.getStatus()).isEqualTo(UserCouponStatus.EXPIRED);
            assertThat(used.getStatus()).isEqualTo(UserCouponStatus.EXPIRED);
            assertThat(used.getReservationId()).isNull();
            assertThat(used.getUsedAt()).isNull();
        }

        @Test
        @DisplayName("이미 돌려준 쿠폰을 다시 돌려주면 그대로다(멱등)")
        void idempotent() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);
            userCoupon.release(NOW);

            userCoupon.release(userCoupon.getExpiresAt().plusDays(1));

            // 만료 시각이 지난 뒤의 재호출이어도 이미 돌려준 쿠폰의 상태는 바꾸지 않는다
            assertThat(userCoupon.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
        }

        @Test
        @DisplayName("돌려준 쿠폰은 다른 예매에 다시 잡을 수 있다")
        void reservableAfterRelease() {
            UserCoupon userCoupon = available();
            userCoupon.reserve(USER_ID, RESERVATION_ID, NOW);
            userCoupon.release(NOW);

            userCoupon.reserve(USER_ID, 8L, NOW);

            assertThat(userCoupon.getReservationId()).isEqualTo(8L);
        }
    }
}
