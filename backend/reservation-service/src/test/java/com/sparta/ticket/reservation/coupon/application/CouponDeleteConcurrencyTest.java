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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 쿠폰 삭제와 첫 발급이 동시에 들어와도 둘 중 하나만 성공하는지 실제 PostgreSQL로 확인한다.
 * 발급 API는 아직 없어서, 발급이 쓸 조건부 UPDATE(삭제되지 않았고 수량이 남았을 때만 1 올림)를 JDBC로 대신 실행한다.
 */
class CouponDeleteConcurrencyTest extends ReservationIntegrationTest {

    private static final int ROUNDS = 30;

    /** 선착순 발급이 쓸 조건부 UPDATE. 발급 이슈에서 같은 조건으로 구현한다. */
    private static final String ISSUE_SQL = """
            UPDATE p_coupons SET issued_quantity = issued_quantity + 1
            WHERE coupon_id = ? AND deleted_at IS NULL AND issued_quantity < total_quantity
            """;

    @Autowired
    private CouponService couponService;

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
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_DELETABLE);
                        return false;
                    }
                });
                Future<Boolean> issue = executor.submit(() -> {
                    start.await();
                    return jdbcTemplate.update(ISSUE_SQL, couponId) == 1;
                });
                start.countDown();

                boolean deleted = delete.get();
                boolean issued = issue.get();
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT issued_quantity, deleted_at FROM p_coupons WHERE coupon_id = ?", couponId);

                assertThat(deleted).as("round %d: 삭제와 발급 중 정확히 하나만 성공", round).isNotEqualTo(issued);
                if (deleted) {
                    assertThat(row.get("deleted_at")).isNotNull();
                    assertThat(row.get("issued_quantity")).isEqualTo(0);
                } else {
                    assertThat(row.get("deleted_at")).isNull();
                    assertThat(row.get("issued_quantity")).isEqualTo(1);
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
