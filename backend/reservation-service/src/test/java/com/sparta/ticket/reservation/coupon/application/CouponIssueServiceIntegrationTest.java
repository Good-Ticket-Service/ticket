package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.dto.IssuedCouponResult;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 발급 서비스의 성공·실패 분기와 실패 때 수량 롤백을 실제 PostgreSQL로 확인한다. 동시 요청은 별도 동시성 테스트에서 본다. */
class CouponIssueServiceIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    /** 서비스와 같이 초 단위로 자른 현재 시각. 다른 테스트와 Clock을 함께 쓰므로 고정값 대신 상대값으로 쓴다. */
    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 한 시간 전에 발급을 시작해 7일 뒤 끝나는(ISSUING) 쿠폰. 유효 일수는 7일이다. */
    private Coupon issuingCoupon(int totalQuantity) {
        return couponRepository.save(Coupon.create(
                "오픈 기념 5,000원 할인", 5_000, totalQuantity, now().minusHours(1), now().plusDays(7), 7, now()));
    }

    private int issuedQuantity(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    private int userCouponCount(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    private static void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    @Test
    @DisplayName("발급하면 AVAILABLE 회원 쿠폰을 돌려주고, 발급 수량이 1 늘어난다")
    void issue() {
        Coupon coupon = issuingCoupon(10);

        IssuedCouponResult result = couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com");

        // 응답은 쿠폰 이름·할인액과 발급 시각 기준의 만료 시각을 담는다
        assertThat(result.userCouponId()).isNotNull();
        assertThat(result.couponId()).isEqualTo(coupon.getId());
        assertThat(result.name()).isEqualTo("오픈 기념 5,000원 할인");
        assertThat(result.discountAmount()).isEqualTo(5_000);
        assertThat(result.status()).isEqualTo(UserCouponStatus.AVAILABLE);
        assertThat(result.issuedAt()).isEqualTo(now());
        assertThat(result.expiresAt()).isEqualTo(now().plusDays(7));
        // 수량 증가와 회원 쿠폰 저장이 함께 커밋됐다
        assertThat(issuedQuantity(coupon.getId())).isEqualTo(1);
        assertThat(userCouponCount(coupon.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원이 다시 받으면 COUPON_ALREADY_ISSUED이고 발급 수량은 늘지 않는다")
    void alreadyIssued() {
        Coupon coupon = issuingCoupon(10);
        couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com");

        assertError(
                () -> couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com"),
                CouponErrorCode.COUPON_ALREADY_ISSUED);
        assertThat(issuedQuantity(coupon.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("수량이 다 떨어지면 COUPON_SOLD_OUT이고 회원 쿠폰을 만들지 않는다")
    void soldOut() {
        Coupon coupon = issuingCoupon(1);
        couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com");

        assertError(
                () -> couponIssueService.issue(coupon.getId(), 2L, "user2@ticket.com"),
                CouponErrorCode.COUPON_SOLD_OUT);
        assertThat(issuedQuantity(coupon.getId())).isEqualTo(1);
        assertThat(userCouponCount(coupon.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("발급 시작 전이면 COUPON_ISSUE_PERIOD_CLOSED다")
    void upcoming() {
        Coupon coupon =
                couponRepository.save(Coupon.create("쿠폰", 5_000, 10, now().plusDays(1), now().plusDays(7), 7, now()));

        assertError(
                () -> couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com"),
                CouponErrorCode.COUPON_ISSUE_PERIOD_CLOSED);
        assertThat(userCouponCount(coupon.getId())).isZero();
    }

    @Test
    @DisplayName("발급을 중단한 쿠폰이면 COUPON_ISSUE_PERIOD_CLOSED다")
    void closed() {
        Coupon coupon = issuingCoupon(10);
        // 발급 중단은 종료 시각을 현재로 당겨, 같은 시각부터 ENDED가 된다
        couponService.close(coupon.getId());

        assertError(
                () -> couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com"),
                CouponErrorCode.COUPON_ISSUE_PERIOD_CLOSED);
    }

    @Test
    @DisplayName("없는 쿠폰과 삭제된 쿠폰은 COUPON_NOT_FOUND다")
    void notFound() {
        Coupon coupon = issuingCoupon(10);
        couponService.delete(coupon.getId(), "master@ticket.com");

        assertError(() -> couponIssueService.issue(-1L, 1L, "user1@ticket.com"), CouponErrorCode.COUPON_NOT_FOUND);
        assertError(
                () -> couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com"),
                CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원은 같은 쿠폰을 각각 받을 수 있다")
    void otherUsers() {
        Coupon coupon = issuingCoupon(10);

        couponIssueService.issue(coupon.getId(), 1L, "user1@ticket.com");
        couponIssueService.issue(coupon.getId(), 2L, "user2@ticket.com");

        assertThat(issuedQuantity(coupon.getId())).isEqualTo(2);
        assertThat(userCouponCount(coupon.getId())).isEqualTo(2);
    }
}
