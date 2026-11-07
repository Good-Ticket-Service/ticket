package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발급과 관리자의 발급 중단(close)이 같은 쿠폰에서 겹칠 때를 실제 PostgreSQL·Redis로 확인한다(#225, #300).
 * 발급은 쿠폰 행 잠금을 쓰지 않아, 중단이 행을 잡고 있어도 기다리지 않는다. 기간은 커밋된 쿠폰 값으로 판단한다.
 * Note: 서비스의 close는 바로 커밋돼 행을 잡아 둘 수 없다. 그래서 서비스와 같은 동작(잠금 조회 후 Coupon.close)을
 * 테스트 트랜잭션에서 하고 커밋을 미룬다. 발급은 서비스(CouponIssueService)를 그대로 부른다.
 */
class CouponCloseConcurrencyTest extends ReservationIntegrationTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private LocalDateTime originalNow;

    /** 요청 시각. 공용 시계를 이 시각에 두고 시작해 시각 비교가 매번 같게 한다. */
    private LocalDateTime now;

    @BeforeEach
    void fixClock() {
        originalNow = clock.now();
        now = originalNow.truncatedTo(ChronoUnit.SECONDS);
        clock.set(now);
    }

    @AfterEach
    void restoreClock() {
        clock.set(originalNow);
    }

    /** 하루 전에 만들어 한 시간 전부터 발급 중인 쿠폰. */
    private Long saveIssuingCoupon() {
        return couponRepository
                .save(Coupon.create("중단 동시성 쿠폰", 1_000, 100, now.minusHours(1), now.plusDays(1), 7, now.minusDays(1)))
                .getId();
    }

    private int userCouponCount(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    @Test
    @DisplayName("중단이 행을 잡고 커밋하기 전의 발급은 기다리지 않고 성공하고, 중단이 커밋된 뒤의 발급은 COUPON_ISSUE_PERIOD_CLOSED다")
    void issueDoesNotWaitForClose() throws Exception {
        Long couponId = saveIssuingCoupon();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // 1) 중단: 행을 잠그고 종료 시각을 당긴 채 커밋하지 않는다
            Future<?> close = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                couponRepository.lockById(couponId).orElseThrow().close(now);
                locked.countDown();
                await(release);
            }));
            locked.await();

            // 2) 그사이 발급은 행 잠금을 기다리지 않고, 아직 커밋되지 않은 중단은 보지 않아 성공한다
            //    (중단 직전에 도착한 요청으로 보고 허용하는 기존 규칙과 같다)
            couponIssueService.issue(couponId, 1L, "user1@ticket.com");

            // 3) 중단을 커밋한 뒤의 발급은 기간 밖이다
            release.countDown();
            close.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> couponIssueService.issue(couponId, 2L, "user2@ticket.com"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CouponErrorCode.COUPON_ISSUE_PERIOD_CLOSED);
            assertThat(userCouponCount(couponId)).isEqualTo(1);
        } finally {
            release(executor);
        }
    }

    private static void release(ExecutorService executor) {
        executor.shutdownNow();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
