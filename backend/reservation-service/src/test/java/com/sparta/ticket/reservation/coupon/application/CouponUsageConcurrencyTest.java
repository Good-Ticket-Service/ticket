package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.facade.CouponUsage;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CouponUsage의 동시 요청과 호출자(예매) 트랜잭션 합류를 실제 PostgreSQL로 확인한다.
 * 동시 요청은 각자 다른 스레드·트랜잭션에서 출발 신호에 맞춰 함께 보낸다.
 * Note: 다른 테스트의 회원 쿠폰도 같은 테이블에 있어, 회원 ID와 예매 ID를 이 테스트만 쓰는 값(99xxxx)으로 둔다.
 */
class CouponUsageConcurrencyTest extends ReservationIntegrationTest {

    private static final int THREADS = 16;

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

    /** 지금 회원에게 발급한, 유효 일수 7일의 쓸 수 있는 회원 쿠폰. */
    private UserCoupon issue(long userId) {
        LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        Coupon coupon =
                couponRepository.save(Coupon.create("동시성 쿠폰", 5_000, 100, now.minusDays(1), now.plusDays(30), 7, now));
        return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, now));
    }

    private Map<String, Object> row(Long userCouponId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id FROM p_user_coupons WHERE user_coupon_id = ?", userCouponId);
    }

    /**
     * 예매 ID마다 같은 쿠폰의 reserve를 동시에 보내고, 결과를 "SUCCESS" 또는 오류 코드 이름으로 세어 돌려준다.
     * 업무 오류가 아닌 예외는 그대로 던져 테스트를 실패시킨다.
     */
    private Map<String, Long> reserveConcurrently(Long userCouponId, long userId, List<Long> reservationIds)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Long reservationId : reservationIds) {
                futures.add(executor.submit(() -> {
                    // 모든 요청이 준비될 때까지 기다렸다가 같이 출발한다
                    start.await();
                    try {
                        couponUsage.reserve(userCouponId, userId, reservationId);
                        return "SUCCESS";
                    } catch (BusinessException e) {
                        return e.getErrorCode().code();
                    }
                }));
            }
            start.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Nested
    @DisplayName("동시 요청")
    class Concurrent {

        @Test
        @DisplayName("같은 쿠폰을 서로 다른 예매 16개가 동시에 잡으면 하나만 성공하고 나머지는 COUPON_NOT_AVAILABLE이다")
        void onlyOneReservationWins() throws Exception {
            UserCoupon issued = issue(990_001L);
            List<Long> reservationIds = new ArrayList<>();
            for (long i = 0; i < THREADS; i++) {
                reservationIds.add(991_000L + i);
            }

            Map<String, Long> results = reserveConcurrently(issued.getId(), 990_001L, reservationIds);

            // FOR UPDATE로 차례로 처리되어 쿠폰 한 장이 두 예매에 쓰이지 않는다
            assertThat(results).containsEntry("SUCCESS", 1L).containsEntry("COUPON_NOT_AVAILABLE", THREADS - 1L);
            Map<String, Object> row = row(issued.getId());
            assertThat(row).containsEntry("status", "RESERVED");
            assertThat(reservationIds).contains((Long) row.get("reservation_id"));
        }

        @Test
        @DisplayName("같은 예매가 같은 쿠폰을 동시에 여러 번 잡아도 모두 성공하고 한 번만 잡힌다(멱등)")
        void duplicateRequestsAreIdempotent() throws Exception {
            UserCoupon issued = issue(990_011L);
            List<Long> sameReservation = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                sameReservation.add(991_100L);
            }

            Map<String, Long> results = reserveConcurrently(issued.getId(), 990_011L, sameReservation);

            // 뒤에 온 요청은 앞 요청이 커밋한 RESERVED(같은 예매)를 읽고 그대로 성공한다
            assertThat(results).containsOnlyKeys("SUCCESS").containsEntry("SUCCESS", (long) THREADS);
            assertThat(row(issued.getId()))
                    .containsEntry("status", "RESERVED")
                    .containsEntry("reservation_id", 991_100L);
        }
    }

    @Nested
    @DisplayName("호출자 트랜잭션 합류(REQUIRED)")
    class CallerTransaction {

        @Test
        @DisplayName("예매 트랜잭션이 롤백되면 그 안에서 잡은 쿠폰도 AVAILABLE로 돌아간다")
        void reserveRollsBackWithCaller() {
            UserCoupon issued = issue(990_021L);

            // 예매가 쿠폰을 잡은 뒤 다른 이유로 실패해 트랜잭션 전체가 롤백되는 상황
            transactionTemplate.executeWithoutResult(status -> {
                couponUsage.reserve(issued.getId(), 990_021L, 991_021L);
                status.setRollbackOnly();
            });

            assertThat(row(issued.getId())).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
        }

        @Test
        @DisplayName("예매 확정 트랜잭션이 롤백되면 사용 처리도 취소되어 RESERVED로 남는다")
        void useRollsBackWithCaller() {
            UserCoupon issued = issue(990_031L);
            couponUsage.reserve(issued.getId(), 990_031L, 991_031L);

            transactionTemplate.executeWithoutResult(status -> {
                couponUsage.use(991_031L);
                status.setRollbackOnly();
            });

            assertThat(row(issued.getId()))
                    .containsEntry("status", "RESERVED")
                    .containsEntry("reservation_id", 991_031L);
        }

        @Test
        @DisplayName("한 트랜잭션에서 release 후 reserve로 쿠폰을 바꾸고, 롤백되면 기존 쿠폰이 그대로 남는다")
        void changeCouponRollsBackTogether() {
            UserCoupon first = issue(990_041L);
            UserCoupon second = issue(990_041L);
            couponUsage.reserve(first.getId(), 990_041L, 991_041L);

            // 쿠폰을 바꾸는 결제 준비가 중간에 실패한 상황: 반환과 새 점유가 함께 취소되어야 한다
            transactionTemplate.executeWithoutResult(status -> {
                couponUsage.release(991_041L);
                couponUsage.reserve(second.getId(), 990_041L, 991_041L);
                status.setRollbackOnly();
            });

            assertThat(row(first.getId())).containsEntry("reservation_id", 991_041L);
            assertThat(row(second.getId())).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
        }
    }
}
