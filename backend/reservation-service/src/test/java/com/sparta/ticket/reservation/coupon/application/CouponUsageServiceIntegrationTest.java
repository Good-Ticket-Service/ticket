package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.facade.CouponDiscount;
import com.sparta.ticket.reservation.coupon.application.facade.CouponUsage;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 예매가 부르는 CouponUsage(reserve·use·release)의 성공·실패·멱등 분기를 실제 PostgreSQL로 확인한다.
 * 동시 요청과 호출자 트랜잭션 롤백은 별도 테스트에서 본다.
 * Note: 다른 테스트의 회원 쿠폰도 같은 테이블에 있어, 회원 ID와 예매 ID를 이 테스트만 쓰는 값(98xxxx)으로 둔다.
 */
class CouponUsageServiceIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private CouponUsage couponUsage;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    /** 서비스와 같이 초 단위로 자른 현재 시각. 다른 테스트와 Clock을 함께 쓰므로 상대값으로 쓴다. */
    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 유효 일수가 7일인 쿠폰을 만들어 issuedAt에 회원에게 발급한다. 만료 시각은 issuedAt + 7일이다. */
    private UserCoupon issueAt(long userId, LocalDateTime issuedAt) {
        Coupon coupon = couponRepository.save(
                Coupon.create("예매 연동 쿠폰", 5_000, 100, now().minusDays(30), now().plusDays(30), 7, now()));
        return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, issuedAt));
    }

    /** 지금 발급해 아직 쓸 수 있는 회원 쿠폰. */
    private UserCoupon issue(long userId) {
        return issueAt(userId, now());
    }

    /** 엔티티를 거치지 않고 DB에 저장된 값을 읽는다. */
    private Map<String, Object> row(Long userCouponId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, used_at FROM p_user_coupons WHERE user_coupon_id = ?", userCouponId);
    }

    private static void assertNotAvailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_AVAILABLE);
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {

        @Test
        @DisplayName("쿠폰을 예매에 잡고 회원 쿠폰 ID·쿠폰 ID·이름·발급 시점 할인액을 돌려준다")
        void reserves() {
            UserCoupon issued = issue(980_001L);

            CouponDiscount discount = couponUsage.reserve(issued.getId(), 980_001L, 981_001L);

            assertThat(discount.userCouponId()).isEqualTo(issued.getId());
            assertThat(discount.couponId()).isEqualTo(issued.getCouponId());
            assertThat(discount.name()).isEqualTo("예매 연동 쿠폰");
            assertThat(discount.discountAmount()).isEqualTo(5_000);
            assertThat(row(issued.getId()))
                    .containsEntry("status", "RESERVED")
                    .containsEntry("reservation_id", 981_001L);
        }

        @Test
        @DisplayName("같은 예매에 같은 쿠폰을 다시 잡으면 같은 값을 돌려주고 상태는 그대로다(멱등)")
        void idempotent() {
            UserCoupon issued = issue(980_011L);
            CouponDiscount first = couponUsage.reserve(issued.getId(), 980_011L, 981_011L);

            CouponDiscount second = couponUsage.reserve(issued.getId(), 980_011L, 981_011L);

            assertThat(second).isEqualTo(first);
            assertThat(row(issued.getId())).containsEntry("reservation_id", 981_011L);
        }

        @Test
        @DisplayName("없는 쿠폰·남의 쿠폰·다른 예매가 잡은 쿠폰·만료된 쿠폰은 COUPON_NOT_AVAILABLE이다")
        void notAvailable() {
            UserCoupon othersCoupon = issue(980_022L);
            UserCoupon takenByOther = issue(980_021L);
            couponUsage.reserve(takenByOther.getId(), 980_021L, 981_021L);
            // 8일 전에 받아 유효 일수(7일)가 지났다. 만료 배치 전이라 저장된 상태는 아직 AVAILABLE이다
            UserCoupon expired = issueAt(980_021L, now().minusDays(8));

            assertNotAvailable(() -> couponUsage.reserve(-1L, 980_021L, 981_022L));
            assertNotAvailable(() -> couponUsage.reserve(othersCoupon.getId(), 980_021L, 981_022L));
            assertNotAvailable(() -> couponUsage.reserve(takenByOther.getId(), 980_021L, 981_022L));
            assertNotAvailable(() -> couponUsage.reserve(expired.getId(), 980_021L, 981_022L));

            // 실패한 요청은 아무것도 바꾸지 않는다
            assertThat(row(othersCoupon.getId())).containsEntry("status", "AVAILABLE");
            assertThat(row(takenByOther.getId())).containsEntry("reservation_id", 981_021L);
            assertThat(row(expired.getId())).containsEntry("status", "AVAILABLE");
        }

        @Test
        @DisplayName("예매에 다른 쿠폰이 잡혀 있으면 IllegalStateException이고, release 뒤에는 바꿀 수 있다")
        void changeCoupon() {
            UserCoupon first = issue(980_031L);
            UserCoupon second = issue(980_031L);
            couponUsage.reserve(first.getId(), 980_031L, 981_031L);

            assertThatThrownBy(() -> couponUsage.reserve(second.getId(), 980_031L, 981_031L))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(row(second.getId())).containsEntry("status", "AVAILABLE");

            // 예매가 쿠폰을 바꿀 때 하는 순서: 기존 쿠폰을 돌려주고 새 쿠폰을 잡는다
            couponUsage.release(981_031L);
            couponUsage.reserve(second.getId(), 980_031L, 981_031L);

            assertThat(row(first.getId())).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
            assertThat(row(second.getId())).containsEntry("reservation_id", 981_031L);
        }
    }

    @Nested
    @DisplayName("use")
    class Use {

        @Test
        @DisplayName("예매에 잡힌 쿠폰을 USED로 바꾸고 사용 시각을 남기며, 다시 불러도 그대로다(멱등)")
        void uses() {
            UserCoupon issued = issue(980_041L);
            couponUsage.reserve(issued.getId(), 980_041L, 981_041L);

            couponUsage.use(981_041L);
            Object usedAt = row(issued.getId()).get("used_at");
            clock.advance(Duration.ofMinutes(5));
            couponUsage.use(981_041L);

            assertThat(row(issued.getId()))
                    .containsEntry("status", "USED")
                    .containsEntry("reservation_id", 981_041L)
                    .containsEntry("used_at", usedAt);
            assertThat(usedAt).isNotNull();
        }

        @Test
        @DisplayName("쿠폰을 쓰지 않은 예매면 아무것도 하지 않는다")
        void noCoupon() {
            couponUsage.use(981_049L);
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("RESERVED 쿠폰을 돌려주면 AVAILABLE이고, 다시 불러도 아무것도 하지 않는다(멱등)")
        void releasesReserved() {
            UserCoupon issued = issue(980_051L);
            couponUsage.reserve(issued.getId(), 980_051L, 981_051L);

            couponUsage.release(981_051L);
            couponUsage.release(981_051L);

            assertThat(row(issued.getId())).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
        }

        @Test
        @DisplayName("USED 쿠폰을 돌려주면(예매 취소) AVAILABLE로 복원되고 사용 시각이 지워진다")
        void releasesUsed() {
            UserCoupon issued = issue(980_061L);
            couponUsage.reserve(issued.getId(), 980_061L, 981_061L);
            couponUsage.use(981_061L);

            couponUsage.release(981_061L);

            assertThat(row(issued.getId()))
                    .containsEntry("status", "AVAILABLE")
                    .containsEntry("reservation_id", null)
                    .containsEntry("used_at", null);
        }

        @Test
        @DisplayName("돌려줄 때 유효 기간이 지났으면 EXPIRED로 복원된다")
        void releasesAsExpired() {
            // 6일 23시간 전에 받아 1시간 뒤 만료되는 쿠폰을 잡고, 만료 뒤에 돌려준다
            UserCoupon issued = issueAt(980_071L, now().minusDays(7).plusHours(1));
            couponUsage.reserve(issued.getId(), 980_071L, 981_071L);
            clock.advance(Duration.ofHours(2));

            couponUsage.release(981_071L);

            assertThat(row(issued.getId())).containsEntry("status", "EXPIRED").containsEntry("reservation_id", null);
        }

        @Test
        @DisplayName("쿠폰을 쓰지 않은 예매면 아무것도 하지 않는다")
        void noCoupon() {
            couponUsage.release(981_079L);
        }
    }
}
