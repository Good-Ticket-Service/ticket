package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 기능과 발급 판단(Redis)이 함께 맞는지 실제 PostgreSQL·Redis로 확인한다(#300).
 * Redis에 사본이 있는 값(총 수량, 삭제 여부)은 Redis에서 먼저 판단·변경하고, DB가 롤백되면 Redis를 되돌린다.
 */
class CouponServiceIssueGateIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 관리자 API와 같이 서비스로 만든 발급 중 쿠폰. 생성이 커밋되면 Redis 정보도 만들어진다. */
    private Long createIssuingCoupon(int totalQuantity) {
        CouponResult created = couponService.create(
                new CreateCouponCommand("관리자 연동 쿠폰", 1_000, totalQuantity, now().minusHours(1), now().plusDays(1), 7));
        return created.couponId();
    }

    private Object redisTotal(Long couponId) {
        return redis.opsForHash().get("coupon:" + couponId + ":meta", "total");
    }

    private int dbTotal(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    private static void assertError(Runnable action, CouponErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    @Test
    @DisplayName("생성하면 Redis에 총 수량이 만들어지고 받은 회원 0명으로 시작한다")
    void createMakesIssueState() {
        Long couponId = createIssuingCoupon(3);

        assertThat(redisTotal(couponId)).isEqualTo("3");
        assertThat(redis.opsForSet().size("coupon:" + couponId + ":users")).isZero();
    }

    @Test
    @DisplayName("생성하면 정보 키가 발급 종료 + 1일까지 남도록 유효 시간을 건다(#323)")
    void createSetsTtl() {
        // 발급 종료가 지금 + 1일이므로 2일 남긴다
        Long couponId = createIssuingCoupon(3);

        long twoDays = Duration.ofDays(2).toSeconds();
        assertThat(redis.getExpire("coupon:" + couponId + ":meta")).isBetween(twoDays - 10, twoDays);
    }

    @Test
    @DisplayName("생성 커밋 직후의 첫 발급 기록이 지워지지 않아 총 수량을 넘겨 발급되지 않는다(#340)")
    void firstIssueRightAfterCommitIsKept() {
        AtomicReference<Long> couponId = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(status -> {
            // 생성보다 먼저 걸어 두어, 커밋되자마자 다른 요청의 첫 발급이 들어온 상황을 만든다.
            // 예전처럼 Redis 정보를 커밋 뒤에 만들면 이 발급이 먼저 끝나고 그 기록을 reset.lua가 지운다
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 다른 요청과 같게 이 트랜잭션에 묶이지 않은 별도 스레드에서 발급한다
                    CompletableFuture.runAsync(() -> couponIssueService.issue(couponId.get(), 1L, "user1@ticket.com"))
                            .join();
                }
            });
            couponId.set(createIssuingCoupon(1));
        });

        assertThat(redis.opsForSet().isMember("coupon:" + couponId.get() + ":users", "1"))
                .isTrue();
        assertError(
                () -> couponIssueService.issue(couponId.get(), 2L, "user2@ticket.com"),
                CouponErrorCode.COUPON_SOLD_OUT);
    }

    @Test
    @DisplayName("생성이 롤백되면 Redis 정보만 남지만 발급은 DB에서 쿠폰을 못 찾아 COUPON_NOT_FOUND다(#340)")
    void createRolledBack() {
        AtomicReference<Long> couponId = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(status -> {
            couponId.set(createIssuingCoupon(5));
            status.setRollbackOnly();
        });

        assertError(
                () -> couponIssueService.issue(couponId.get(), 1L, "user1@ticket.com"),
                CouponErrorCode.COUPON_NOT_FOUND);
        // ID는 롤백해도 다시 쓰이지 않아, 남은 정보를 다음 쿠폰이 물려받지 않는다
        assertThat(createIssuingCoupon(5)).isNotEqualTo(couponId.get());
    }

    @Test
    @DisplayName("총 수량을 받은 회원 수보다 적게 바꾸면 COUPON_QUANTITY_BELOW_ISSUED이고 DB·Redis 모두 그대로다")
    void totalBelowIssued() {
        Long couponId = createIssuingCoupon(10);
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
        couponIssueService.issue(couponId, 2L, "user2@ticket.com");

        assertError(
                () -> couponService.update(couponId, new UpdateCouponCommand(null, null, 1, null, null, null)),
                CouponErrorCode.COUPON_QUANTITY_BELOW_ISSUED);

        assertThat(dbTotal(couponId)).isEqualTo(10);
        assertThat(redisTotal(couponId)).isEqualTo("10");
    }

    @Test
    @DisplayName("총 수량을 바꾸면 Redis도 바뀌어 바뀐 수량까지만 발급되고, 응답의 남은 수량은 받은 회원 수로 계산한다")
    void totalChanged() {
        Long couponId = createIssuingCoupon(10);
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");

        CouponResult result = couponService.update(couponId, new UpdateCouponCommand(null, null, 2, null, null, null));

        assertThat(result.remainingQuantity()).isEqualTo(1);
        couponIssueService.issue(couponId, 2L, "user2@ticket.com");
        assertError(() -> couponIssueService.issue(couponId, 3L, "user3@ticket.com"), CouponErrorCode.COUPON_SOLD_OUT);
    }

    @Test
    @DisplayName("총 수량 변경의 DB 저장이 롤백되면 Redis 총 수량도 되돌린다")
    void totalRestoredOnRollback() {
        Long couponId = createIssuingCoupon(10);

        transactionTemplate.executeWithoutResult(status -> {
            couponService.update(couponId, new UpdateCouponCommand(null, null, 20, null, null, null));
            status.setRollbackOnly();
        });

        assertThat(dbTotal(couponId)).isEqualTo(10);
        assertThat(redisTotal(couponId)).isEqualTo("10");
    }

    @Test
    @DisplayName("받은 회원이 있으면 삭제는 COUPON_NOT_DELETABLE이다")
    void deleteWithIssued() {
        Long couponId = createIssuingCoupon(10);
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");

        assertError(() -> couponService.delete(couponId, "master@ticket.com"), CouponErrorCode.COUPON_NOT_DELETABLE);
    }

    @Test
    @DisplayName("삭제의 DB 저장이 롤백되면 Redis 삭제 표시를 지워 다시 발급된다")
    void deleteUnmarkedOnRollback() {
        Long couponId = createIssuingCoupon(10);

        transactionTemplate.executeWithoutResult(status -> {
            couponService.delete(couponId, "master@ticket.com");
            status.setRollbackOnly();
        });

        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId))
                .isEqualTo(1);
    }
}
