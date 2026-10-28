package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 선착순 발급에 요청이 동시에 몰려도 총 수량과 1인 1장이 지켜지는지 실제 PostgreSQL로 확인한다.
 * 요청을 한꺼번에 출발시키려고 모든 스레드가 CountDownLatch 하나를 기다렸다가 동시에 발급을 부른다.
 */
class CouponIssueConcurrencyTest extends ReservationIntegrationTest {

    private static final int THREADS = 32;

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

    private Long saveIssuingCoupon(int totalQuantity) {
        LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        return couponRepository
                .save(Coupon.create("동시성 쿠폰", 1_000, totalQuantity, now.minusHours(1), now.plusDays(1), 7, now))
                .getId();
    }

    /**
     * 회원 ID 목록대로 발급을 동시에 요청하고, 결과를 "SUCCESS" 또는 오류 코드 이름으로 세어 돌려준다.
     * 예상하지 못한 예외(업무 오류가 아닌 것)는 그대로 던져 테스트를 실패시킨다.
     */
    private Map<String, Long> issueConcurrently(Long couponId, List<Long> userIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Long userId : userIds) {
                futures.add(executor.submit(() -> {
                    // 모든 요청이 준비될 때까지 기다렸다가 같이 출발한다
                    start.await();
                    try {
                        couponIssueService.issue(couponId, userId, "user" + userId + "@ticket.com");
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

    private int issuedQuantity(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    private int userCouponCount(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    private static String code(ErrorCode errorCode) {
        return errorCode.code();
    }

    @Test
    @DisplayName("수량 10장에 회원 50명이 동시에 요청하면 정확히 10명만 받고 나머지 40명은 COUPON_SOLD_OUT이다")
    void notOverIssued() throws Exception {
        Long couponId = saveIssuingCoupon(10);
        List<Long> userIds = new ArrayList<>();
        for (long userId = 1; userId <= 50; userId++) {
            userIds.add(userId);
        }

        Map<String, Long> results = issueConcurrently(couponId, userIds);

        assertThat(results).containsEntry("SUCCESS", 10L).containsEntry(code(CouponErrorCode.COUPON_SOLD_OUT), 40L);
        // 발급 수량과 실제 회원 쿠폰 수가 총 수량과 같다. 초과 발급도, 수량과 쿠폰 수의 불일치도 없다
        assertThat(issuedQuantity(couponId)).isEqualTo(10);
        assertThat(userCouponCount(couponId)).isEqualTo(10);
    }

    @Test
    @DisplayName("같은 회원이 동시에 10번 요청하면 1장만 받고 나머지는 COUPON_ALREADY_ISSUED이며 수량도 1이다")
    void onePerUser() throws Exception {
        Long couponId = saveIssuingCoupon(100);
        List<Long> sameUser = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sameUser.add(7L);
        }

        Map<String, Long> results = issueConcurrently(couponId, sameUser);

        // 사전 확인을 함께 통과한 요청도 UNIQUE 위반으로 실패하고, 그 요청이 올린 수량은 롤백된다.
        // Note: 늦게 출발한 요청은 사전 확인에서 바로 막힐 수 있어, 이 테스트가 UNIQUE 경로를 매번 지난다는 보장은 없다.
        // "수량 UPDATE 뒤 UNIQUE 위반 → 수량 롤백"의 결정적 검증은 CouponIssuePersistenceIntegrationTest가 한다
        assertThat(results).containsEntry("SUCCESS", 1L).containsEntry(code(CouponErrorCode.COUPON_ALREADY_ISSUED), 9L);
        assertThat(issuedQuantity(couponId)).isEqualTo(1);
        assertThat(userCouponCount(couponId)).isEqualTo(1);
    }

    @Test
    @DisplayName("발급과 관리자 수정(이름·총 수량)이 겹쳐도 발급 수량과 회원 쿠폰 수가 같고, 총 수량을 넘지 않는다")
    void issueWhileAdminUpdates() throws Exception {
        Long couponId = saveIssuingCoupon(100);
        ExecutorService admin = Executors.newSingleThreadExecutor();
        try {
            // 관리자는 이름을 여러 번 바꾸고, 중간에 총 수량을 20으로 줄이려 한다.
            // 이미 20장보다 많이 나갔으면 COUPON_QUANTITY_BELOW_ISSUED로 거절되는 것이 정상이다
            Future<?> adminWork = admin.submit(() -> {
                for (int i = 0; i < 5; i++) {
                    Integer total = i == 2 ? 20 : null;
                    try {
                        couponService.update(
                                couponId, new UpdateCouponCommand("이름 " + i, null, total, null, null, null));
                    } catch (BusinessException e) {
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_QUANTITY_BELOW_ISSUED);
                    }
                }
                return null;
            });
            List<Long> userIds = new ArrayList<>();
            for (long userId = 1; userId <= 40; userId++) {
                userIds.add(userId);
            }

            Map<String, Long> results = issueConcurrently(couponId, userIds);
            adminWork.get();

            // 실패는 소진뿐이다(총 수량이 20으로 줄었을 때)
            assertThat(results.keySet()).isSubsetOf("SUCCESS", code(CouponErrorCode.COUPON_SOLD_OUT));
            // 관리자 수정의 변경 감지 UPDATE가 옛 발급 수량으로 덮어쓰지 않았다(issued_quantity updatable = false)
            int issued = issuedQuantity(couponId);
            assertThat(issued)
                    .isEqualTo(userCouponCount(couponId))
                    .isEqualTo(results.get("SUCCESS").intValue());
            int total = jdbcTemplate.queryForObject(
                    "SELECT total_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, couponId);
            assertThat(issued).isLessThanOrEqualTo(total);
        } finally {
            admin.shutdownNow();
        }
    }
}
