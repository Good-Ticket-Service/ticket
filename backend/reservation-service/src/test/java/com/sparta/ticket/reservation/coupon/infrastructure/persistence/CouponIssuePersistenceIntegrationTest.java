package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 발급에 쓰는 저장소 기능(회원 쿠폰 저장, Redis를 다시 채울 때와 남은 수량을 셀 때의 조회)을 실제 PostgreSQL로 확인한다. */
class CouponIssuePersistenceIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final LocalDateTime START = NOW.minusDays(1);
    private static final LocalDateTime END = NOW.plusDays(7);

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    private Coupon saveCoupon(int totalQuantity) {
        return couponRepository.save(Coupon.create("쿠폰", 5_000, totalQuantity, START, END, 7, START.minusDays(1)));
    }

    @Nested
    @DisplayName("회원 쿠폰 저장")
    class SaveIssued {

        @Test
        @DisplayName("저장하면 ID가 채워지고, 같은 회원·쿠폰으로 받았는지 확인할 수 있다")
        void saveAndExists() {
            Coupon coupon = saveCoupon(10);

            UserCoupon saved = userCouponRepository.saveIssued(UserCoupon.issue(1L, coupon, NOW));

            assertThat(saved.getId()).isNotNull();
            assertThat(userCouponRepository.existsByUserIdAndCouponId(1L, coupon.getId()))
                    .isTrue();
            assertThat(userCouponRepository.existsByUserIdAndCouponId(2L, coupon.getId()))
                    .isFalse();
        }

        @Test
        @DisplayName("같은 회원이 같은 쿠폰을 또 저장하면 COUPON_ALREADY_ISSUED다")
        void duplicate() {
            Coupon coupon = saveCoupon(10);
            userCouponRepository.saveIssued(UserCoupon.issue(1L, coupon, NOW));

            assertThatThrownBy(() -> userCouponRepository.saveIssued(UserCoupon.issue(1L, coupon, NOW)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CouponErrorCode.COUPON_ALREADY_ISSUED);
        }
    }

    @Nested
    @DisplayName("받은 회원 조회")
    class IssuedUsers {

        @Test
        @DisplayName("쿠폰을 받은 회원 ID를 모두 읽는다. 다른 쿠폰의 회원은 섞이지 않는다")
        void userIds() {
            Coupon coupon = saveCoupon(10);
            Coupon other = saveCoupon(10);
            userCouponRepository.saveIssued(UserCoupon.issue(1L, coupon, NOW));
            userCouponRepository.saveIssued(UserCoupon.issue(2L, coupon, NOW));
            userCouponRepository.saveIssued(UserCoupon.issue(3L, other, NOW));

            assertThat(userCouponRepository.findUserIdsByCouponId(coupon.getId()))
                    .containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("쿠폰별 회원 쿠폰 수를 한 번에 센다. 받은 회원이 없는 쿠폰은 결과에 없다")
        void counts() {
            Coupon two = saveCoupon(10);
            Coupon one = saveCoupon(10);
            Coupon none = saveCoupon(10);
            userCouponRepository.saveIssued(UserCoupon.issue(1L, two, NOW));
            userCouponRepository.saveIssued(UserCoupon.issue(2L, two, NOW));
            userCouponRepository.saveIssued(UserCoupon.issue(1L, one, NOW));

            assertThat(userCouponRepository.countByCouponIds(List.of(two.getId(), one.getId(), none.getId())))
                    .containsEntry(two.getId(), 2)
                    .containsEntry(one.getId(), 1)
                    .doesNotContainKey(none.getId());
        }
    }
}
