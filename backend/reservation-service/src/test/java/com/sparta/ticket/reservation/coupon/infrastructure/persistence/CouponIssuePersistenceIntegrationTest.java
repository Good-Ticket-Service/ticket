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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 발급에 쓰는 저장소 기능(수량 조건부 UPDATE, 회원 쿠폰 저장)을 실제 PostgreSQL로 확인한다. */
class CouponIssuePersistenceIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final LocalDateTime START = NOW.minusDays(1);
    private static final LocalDateTime END = NOW.plusDays(7);

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 발급 시작 전에 만든 쿠폰. 과거 시작 시각은 현재로 맞춰 저장되므로, 시작 경계를 보려면 시작 전에 만든다. */
    private Coupon saveCoupon(int totalQuantity) {
        return couponRepository.save(Coupon.create("쿠폰", 5_000, totalQuantity, START, END, 7, START.minusDays(1)));
    }

    /** 조건부 UPDATE는 트랜잭션 안에서 불러야 한다. 발급 서비스도 자기 트랜잭션 안에서 부른다. */
    private int increase(Long couponId, LocalDateTime now) {
        return transactionTemplate.execute(status -> couponRepository.increaseIssuedQuantity(couponId, now, "user1"));
    }

    private int issuedQuantity(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    @Nested
    @DisplayName("발급 수량 조건부 UPDATE")
    class IncreaseIssuedQuantity {

        @Test
        @DisplayName("발급 중이고 수량이 남았으면 1행을 바꾸고 발급 수량과 수정자를 갱신한다")
        void increases() {
            Coupon coupon = saveCoupon(10);

            assertThat(increase(coupon.getId(), NOW)).isEqualTo(1);

            assertThat(issuedQuantity(coupon.getId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT updated_by FROM p_coupons WHERE coupon_id = ?", String.class, coupon.getId()))
                    .isEqualTo("user1");
        }

        @Test
        @DisplayName("총 수량만큼 발급하면 다음 요청은 0행이고 발급 수량은 그대로다")
        void soldOut() {
            Coupon coupon = saveCoupon(2);
            increase(coupon.getId(), NOW);
            increase(coupon.getId(), NOW);

            assertThat(increase(coupon.getId(), NOW)).isZero();
            assertThat(issuedQuantity(coupon.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("시작 시각 정각에는 발급되고, 1초 전에는 0행이다")
        void startBoundary() {
            Coupon coupon = saveCoupon(10);

            assertThat(increase(coupon.getId(), START.minusSeconds(1))).isZero();
            assertThat(increase(coupon.getId(), START)).isEqualTo(1);
        }

        @Test
        @DisplayName("종료 시각 1초 전에는 발급되고, 종료 시각 정각부터는 0행이다")
        void endBoundary() {
            Coupon coupon = saveCoupon(10);

            assertThat(increase(coupon.getId(), END.minusSeconds(1))).isEqualTo(1);
            assertThat(increase(coupon.getId(), END)).isZero();
        }

        @Test
        @DisplayName("삭제된 쿠폰은 0행이다")
        void deleted() {
            Coupon coupon = saveCoupon(10);
            transactionTemplate.executeWithoutResult(status ->
                    couponRepository.lockById(coupon.getId()).orElseThrow().delete("master"));

            assertThat(increase(coupon.getId(), NOW)).isZero();
        }

        @Test
        @DisplayName("없는 쿠폰은 0행이다")
        void notFound() {
            assertThat(increase(-1L, NOW)).isZero();
        }
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

        @Test
        @DisplayName("한 트랜잭션에서 수량을 올린 뒤 중복으로 실패하면 올린 수량도 롤백된다")
        void duplicateRollsBackIncrease() {
            Coupon coupon = saveCoupon(10);
            transactionTemplate.executeWithoutResult(status -> {
                couponRepository.increaseIssuedQuantity(coupon.getId(), NOW, "user1");
                userCouponRepository.saveIssued(UserCoupon.issue(1L, coupon, NOW));
            });
            assertThat(issuedQuantity(coupon.getId())).isEqualTo(1);

            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                        couponRepository.increaseIssuedQuantity(coupon.getId(), NOW, "user1");
                        userCouponRepository.saveIssued(UserCoupon.issue(1L, coupon, NOW));
                    }))
                    .isInstanceOf(BusinessException.class);
            assertThat(issuedQuantity(coupon.getId())).isEqualTo(1);
        }
    }
}
