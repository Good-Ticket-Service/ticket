package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
 * 발급과 관리자의 발급 중단(close)이 같은 쿠폰에서 겹칠 때를 실제 PostgreSQL로 확인한다(#225).
 * 한쪽이 쿠폰 행을 잡은 채 멈춘 사이 다른 쪽을 보내고, 락 대기에 들어간 것을 pg_stat_activity로 확인한 뒤 풀어 순서를 고정한다.
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

    private Map<String, Object> row(Long couponId) {
        return jdbcTemplate.queryForMap(
                "SELECT issued_quantity, issue_end_at FROM p_coupons WHERE coupon_id = ?", couponId);
    }

    private int userCouponCount(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    @Test
    @DisplayName("중단이 행을 잡은 동안 기다린 발급은 중단 전에 도착했으면 성공하고, 중단 뒤에 도착한 발급은 COUPON_ISSUE_PERIOD_CLOSED다")
    void closeFirst() throws Exception {
        Long couponId = saveIssuingCoupon();
        LocalDateTime closedAt = now.plusSeconds(10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch closed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // 관리자가 closedAt에 중단한다. 행을 잡은 채 release 신호가 올 때까지 커밋하지 않는다
            Future<?> admin = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                couponRepository.lockById(couponId).orElseThrow().close(closedAt);
                closed.countDown();
                await(release);
            }));
            closed.await();

            // 중단 전(now)에 도착한 발급. 요청 시각을 정한 뒤 수량 UPDATE에서 중단의 락을 기다린다
            Future<ErrorCode> beforeClose = executor.submit(() -> issue(couponId, 1L));
            waitForLockWaiters(1);

            release.countDown();
            admin.get();

            // 중단이 커밋된 뒤 발급의 UPDATE는 WHERE를 다시 본다. issue_end_at(closedAt) > 요청 시각(now)이라 발급된다
            assertThat(beforeClose.get()).isNull();
            assertThat(row(couponId))
                    .containsEntry("issued_quantity", 1)
                    .containsEntry("issue_end_at", Timestamp.valueOf(closedAt));

            // 중단 뒤에 도착한 발급은 기간 밖이다
            clock.set(closedAt.plusSeconds(1));
            assertThat(issue(couponId, 2L)).isEqualTo(CouponErrorCode.COUPON_ISSUE_PERIOD_CLOSED);
            assertThat(userCouponCount(couponId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("발급이 행을 잡은 동안 기다린 중단은 발급이 끝난 뒤 성공하고, 먼저 나간 쿠폰은 그대로 남는다")
    void issueFirst() throws Exception {
        Long couponId = saveIssuingCoupon();
        LocalDateTime closedAt = now.plusSeconds(10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch issued = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // 발급과 같은 수량 UPDATE로 행을 잡은 채 커밋하지 않는다
            Future<Integer> issue = executor.submit(() -> transactionTemplate.execute(status -> {
                int increased = couponRepository.increaseIssuedQuantity(couponId, now, "user1@ticket.com");
                issued.countDown();
                await(release);
                return increased;
            }));
            issued.await();

            // 중단은 잠금 조회(FOR UPDATE)에서 발급의 락을 기다린다
            Future<?> admin = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> couponRepository.lockById(couponId).orElseThrow().close(closedAt)));
            waitForLockWaiters(1);

            release.countDown();
            assertThat(issue.get()).isEqualTo(1);
            admin.get();

            // 발급이 올린 수량은 그대로이고, 중단은 그 뒤에 종료 시각을 당겼다
            assertThat(row(couponId))
                    .containsEntry("issued_quantity", 1)
                    .containsEntry("issue_end_at", Timestamp.valueOf(closedAt));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 발급을 부르고 성공이면 null, 업무 오류면 오류 코드를 돌려준다. */
    private ErrorCode issue(Long couponId, long userId) {
        try {
            couponIssueService.issue(couponId, userId, "user" + userId + "@ticket.com");
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
}
