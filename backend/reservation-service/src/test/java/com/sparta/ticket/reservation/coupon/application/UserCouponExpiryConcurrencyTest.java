package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.config.HeaderAuditorAware;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.facade.CouponUsage;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 배치와 예매의 쿠폰 점유(CouponUsage.reserve)가 같은 회원 쿠폰에서 겹칠 때를 실제 PostgreSQL로 확인한다.
 * 한쪽이 행 락을 잡은 채 멈춘 사이 다른 쪽을 보내 락 대기에 들어간 것을 확인한 뒤 풀어 준다. 그래서 어느 쪽이 먼저인지가 매번 같다.
 * 상황은 "만료 시각 직전에 예매가 쿠폰을 잡는 중에 만료 배치가 돈다"이다. 예매는 만료 전 시각, 배치는 만료 시각으로 실행한다.
 * Note: 다른 테스트의 쿠폰이 섞이지 않도록 과거 시각(2001년)과 이 테스트만 쓰는 회원 ID(943xxx)를 쓰고, 공용 시계는 끝나면 돌린다.
 */
class UserCouponExpiryConcurrencyTest extends ReservationIntegrationTest {

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2001, 9, 1, 12, 0);

    /** 유효 일수 7일. 이 시각 정각부터 만료다. */
    private static final LocalDateTime EXPIRES_AT = ISSUED_AT.plusDays(7);

    @Autowired
    private UserCouponExpiryService expiryService;

    @Autowired
    private CouponUsage couponUsage;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private LocalDateTime originalNow;

    @BeforeEach
    void keepClock() {
        originalNow = clock.now();
    }

    @AfterEach
    void restore() {
        clock.set(originalNow);
        jdbcTemplate.update("DELETE FROM p_user_coupons WHERE user_id BETWEEN 943000 AND 943999");
    }

    private UserCoupon issue(long userId) {
        Coupon coupon = couponRepository.save(
                Coupon.create("만료 동시성 쿠폰", 5_000, 100, ISSUED_AT.minusDays(1), ISSUED_AT.plusDays(30), 7, ISSUED_AT));
        return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, ISSUED_AT));
    }

    private String status(Long userCouponId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_user_coupons WHERE user_coupon_id = ?", String.class, userCouponId);
    }

    @Test
    @DisplayName("예매가 먼저 쿠폰을 잡고 있으면 배치는 기다린 뒤 RESERVED를 보고 건너뛴다")
    void reserveFirst() throws Exception {
        UserCoupon coupon = issue(943_001L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch reserved = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // 만료 직전에 예매가 쿠폰을 잡는다. release 신호가 올 때까지 커밋하지 않는다
            Future<?> booking = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                userCouponRepository
                        .lockById(coupon.getId())
                        .orElseThrow()
                        .reserve(943_001L, 944_001L, EXPIRES_AT.minusSeconds(1));
                reserved.countDown();
                await(release);
            }));
            reserved.await();

            // 배치는 잠그지 않고 후보를 고른 뒤 UPDATE에서 예매의 락을 기다린다
            clock.set(EXPIRES_AT);
            Future<Integer> batch = executor.submit(() -> expiryService.expireDue(10, Duration.ofSeconds(30)));
            waitForLockWaiters(1);

            release.countDown();
            booking.get();

            // 예매가 커밋한 뒤 배치는 WHERE를 다시 보고 AVAILABLE이 아니라 건너뛴다
            assertThat(batch.get()).isZero();
            assertThat(status(coupon.getId())).isEqualTo("RESERVED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("배치가 먼저 EXPIRED로 바꾸고 있으면 예매는 기다린 뒤 EXPIRED를 보고 거절된다")
    void expireFirst() throws Exception {
        UserCoupon coupon = issue(943_011L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch expired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // 배치의 한 묶음 트랜잭션. EXPIRED로 바꾼 뒤 release 신호가 올 때까지 커밋하지 않는다
            Future<Integer> batch = executor.submit(() -> transactionTemplate.execute(status -> {
                int changed =
                        userCouponRepository.expire(List.of(coupon.getId()), EXPIRES_AT, HeaderAuditorAware.SYSTEM);
                expired.countDown();
                await(release);
                return changed;
            }));
            expired.await();

            // 예매는 만료 직전 시각으로 잡으려 한다. 시각만 보면 아직 쓸 수 있지만 배치의 락을 기다린다
            clock.set(EXPIRES_AT.minusSeconds(1));
            Future<ErrorCode> booking =
                    executor.submit(() -> run(() -> couponUsage.reserve(coupon.getId(), 943_011L, 944_011L)));
            waitForLockWaiters(1);

            release.countDown();

            // 배치가 커밋한 뒤 예매는 EXPIRED를 읽고 거절된다
            assertThat(batch.get()).isEqualTo(1);
            assertThat(booking.get()).isEqualTo(CouponErrorCode.COUPON_NOT_AVAILABLE);
            assertThat(status(coupon.getId())).isEqualTo("EXPIRED");
        } finally {
            executor.shutdownNow();
        }
    }

    /** 작업을 실행하고 성공이면 null, 업무 오류면 오류 코드를 돌려준다. */
    private static ErrorCode run(Runnable action) {
        try {
            action.run();
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    /** p_user_coupons의 행 락을 기다리는 세션이 count개가 될 때까지 기다린다. 10초가 지나면 실패한다. */
    private void waitForLockWaiters(int count) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%p_user_coupons%'
                    """, Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("락 대기 세션이 " + count + "개가 되지 않았다");
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
