package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 쿠폰 삭제와 첫 발급이 동시에 들어와도 둘 중 하나만 성공하는지 실제 PostgreSQL·Redis로 확인한다.
 * 발급은 실제 발급 서비스(CouponIssueService)를 부른다. 삭제 표시(mark-deleted.lua)와 발급 판단(issue.lua)이 같은 Redis 키를 보므로
 * Redis가 둘을 차례로 실행한다(#300).
 */
class CouponDeleteConcurrencyTest extends ReservationIntegrationTest {

    private static final int ROUNDS = 30;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @Test
    @DisplayName("삭제와 첫 발급이 동시에 오면 삭제만 되거나 발급만 된다 (삭제된 쿠폰에 발급되는 일이 없다)")
    void deleteAndFirstIssueAreExclusive() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Long couponId = saveIssuingCoupon();
                CountDownLatch start = new CountDownLatch(1);

                Future<Boolean> delete = executor.submit(() -> {
                    start.await();
                    try {
                        couponService.delete(couponId, "master@ticket.com");
                        return true;
                    } catch (BusinessException e) {
                        // 발급이 먼저 Redis에 기록되면 받은 회원이 있어 지울 수 없다
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_DELETABLE);
                        return false;
                    }
                });
                Future<Boolean> issue = executor.submit(() -> {
                    start.await();
                    try {
                        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
                        return true;
                    } catch (BusinessException e) {
                        // 삭제가 먼저면 DB 조회(삭제 커밋 뒤)나 Redis 삭제 표시(커밋 전)에서 막혀 404다
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
                        return false;
                    }
                });
                start.countDown();

                boolean deleted = delete.get();
                boolean issued = issue.get();

                assertThat(deleted).as("round %d: 삭제와 발급 중 정확히 하나만 성공", round).isNotEqualTo(issued);
                Integer userCoupons = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
                Integer alive = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_coupons WHERE coupon_id = ? AND deleted_at IS NULL",
                        Integer.class,
                        couponId);
                if (deleted) {
                    // 삭제가 이겼으면 회원 쿠폰이 남지 않는다
                    assertThat(alive).isZero();
                    assertThat(userCoupons).isZero();
                } else {
                    // 발급이 이겼으면 쿠폰은 남아 있고 회원 쿠폰이 1장이다
                    assertThat(alive).isEqualTo(1);
                    assertThat(userCoupons).isEqualTo(1);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Long saveIssuingCoupon() {
        LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        return couponRepository
                .save(Coupon.create("동시성 쿠폰", 1_000, 10, now.minusHours(1), now.plusDays(1), 7, now))
                .getId();
    }
}
