package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 쿠폰 삭제와 첫 발급이 동시에 들어와도 둘 중 하나만 성공하는지 실제 PostgreSQL로 확인한다.
 * 발급은 실제 발급 서비스(CouponIssueService)를 부른다. 두 요청 모두 쿠폰 행의 조건부 UPDATE라 같은 행 락을 차례로 잡는다.
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

    @Autowired
    private TransactionTemplate transactionTemplate;

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
                        // 삭제가 먼저 커밋되면 발급의 UPDATE는 deleted_at 조건으로 0행이 되고, 삭제된 쿠폰이라 404다
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
                        return false;
                    }
                });
                start.countDown();

                boolean deleted = delete.get();
                boolean issued = issue.get();
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT issued_quantity, deleted_at FROM p_coupons WHERE coupon_id = ?", couponId);

                assertThat(deleted).as("round %d: 삭제와 발급 중 정확히 하나만 성공", round).isNotEqualTo(issued);
                Integer userCoupons = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
                if (deleted) {
                    // 삭제가 이겼으면 발급 흔적(수량, 회원 쿠폰)이 남지 않는다
                    assertThat(row.get("deleted_at")).isNotNull();
                    assertThat(row.get("issued_quantity")).isEqualTo(0);
                    assertThat(userCoupons).isZero();
                } else {
                    // 발급이 이겼으면 쿠폰은 남아 있고 수량과 회원 쿠폰이 함께 1이다
                    assertThat(row.get("deleted_at")).isNull();
                    assertThat(row.get("issued_quantity")).isEqualTo(1);
                    assertThat(userCoupons).isEqualTo(1);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("발급이 먼저 락을 잡으면 발급은 성공하고, 기다리던 삭제는 다시 평가해 COUPON_NOT_DELETABLE이다")
    void issueFirstThenDelete() throws Exception {
        Long couponId = saveIssuingCoupon();

        Results results = runInLockOrder(
                couponId,
                () -> couponIssueService.issue(couponId, 1L, "user1@ticket.com"),
                () -> couponService.delete(couponId, "master@ticket.com"));

        assertThat(results.first()).isNull();
        assertThat(results.second()).isEqualTo(CouponErrorCode.COUPON_NOT_DELETABLE);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT issued_quantity FROM p_coupons WHERE coupon_id = ? AND deleted_at IS NULL",
                        Integer.class,
                        couponId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("삭제가 먼저 락을 잡으면 삭제는 성공하고, 기다리던 발급은 다시 평가해 COUPON_NOT_FOUND이다")
    void deleteFirstThenIssue() throws Exception {
        Long couponId = saveIssuingCoupon();

        Results results = runInLockOrder(
                couponId,
                () -> couponService.delete(couponId, "master@ticket.com"),
                () -> couponIssueService.issue(couponId, 1L, "user1@ticket.com"));

        assertThat(results.first()).isNull();
        assertThat(results.second()).isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId))
                .isZero();
    }

    /** 두 작업의 결과. 성공이면 null, 업무 오류면 그 오류 코드다. */
    private record Results(ErrorCode first, ErrorCode second) {}

    /**
     * 두 작업이 쿠폰 행 락을 정해진 순서로 잡게 해서 실행한다.
     * 1) 별도 트랜잭션이 SELECT ... FOR UPDATE로 행을 먼저 잡아 둔다.
     * 2) first를 출발시켜 락 대기에 들어간 것을 확인한 뒤 second를 출발시킨다.
     * 3) 잡아 둔 락을 풀면 PostgreSQL이 기다린 순서대로 first → second에 락을 넘긴다.
     * Note: 동시에 출발시키면 어느 쪽이 먼저 락을 잡을지 정할 수 없어, 한쪽 순서가 한 번도 실행되지 않아도 테스트가 통과할 수 있다.
     */
    private Results runInLockOrder(Long couponId, Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // 락을 잡아 두는 트랜잭션. release 신호가 올 때까지 커밋하지 않는다
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT coupon_id FROM p_coupons WHERE coupon_id = ? FOR UPDATE", Long.class, couponId);
                locked.countDown();
                await(release);
            }));
            locked.await();

            // first가 락 대기에 들어간 것을 확인한 뒤 second를 보내 대기 순서를 정한다
            Future<ErrorCode> firstResult = executor.submit(() -> run(first));
            waitForLockWaiters(1);
            Future<ErrorCode> secondResult = executor.submit(() -> run(second));
            waitForLockWaiters(2);

            release.countDown();
            holder.get();
            return new Results(firstResult.get(), secondResult.get());
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

    /** p_coupons의 행 락을 기다리는 세션이 count개가 될 때까지 기다린다. 10초가 지나면 실패한다. */
    private void waitForLockWaiters(int count) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%p_coupons%'
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

    private Long saveIssuingCoupon() {
        LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        return couponRepository
                .save(Coupon.create("동시성 쿠폰", 1_000, 10, now.minusHours(1), now.plusDays(1), 7, now))
                .getId();
    }
}
