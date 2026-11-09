package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.CouponIssueReconcileService.Result;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 맞추는 작업(#323)이 Redis 사본을 DB 원본에 맞추는지 실제 PostgreSQL·Redis로 확인한다.
 * 어긋남은 그 자리 되돌리기를 못 한 경우(서버가 죽음, 연결 끊김)를 흉내 내 Redis·DB에 직접 만든다.
 * Note: 대상은 DB의 모든 쿠폰이라 다른 테스트의 쿠폰도 함께 맞춰진다. 그래서 결과 수 대신 이 테스트 쿠폰의 상태를 본다.
 */
class CouponIssueReconcileServiceIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private CouponIssueReconcileService reconcileService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private LocalDateTime start;

    @BeforeEach
    void rememberClock() {
        start = clock.now();
    }

    /** 확인 대기를 위해 옮긴 시계를 되돌려 다른 테스트에 영향이 없게 한다. */
    @AfterEach
    void restoreClock() {
        clock.set(start);
    }

    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 관리자 API와 같이 서비스로 만든 발급 중 쿠폰. 생성이 커밋되면 Redis 정보도 만들어진다. */
    private Long createIssuingCoupon(int totalQuantity) {
        return couponService
                .create(new CreateCouponCommand(
                        "맞추기 쿠폰", 1_000, totalQuantity, now().minusHours(1), now().plusDays(1), 7))
                .couponId();
    }

    private static String meta(Long couponId) {
        return "coupon:" + couponId + ":meta";
    }

    private static String users(Long couponId) {
        return "coupon:" + couponId + ":users";
    }

    /** Redis를 거치지 않고 DB에만 회원 쿠폰을 넣는다(Redis가 이 회원을 모르는 상태). */
    private void saveOnlyInDb(Long couponId, long userId) {
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, now()));
    }

    private int issuedQuantity(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }

    @Test
    @DisplayName("Redis에만 남은 회원은 처음 본 뒤 1분이 지나 다시 확인될 때 지워 그 자리가 다시 빈다")
    void removesRedisOnlyAfterConfirm() {
        // 저장 실패 뒤 되돌리기를 못 해 Redis에만 남은 회원 99. 한 자리를 차지하고 있다
        Long couponId = createIssuingCoupon(1);
        redis.opsForSet().add(users(couponId), "99");

        // 처음 보면 기억만 하고, 1분이 안 지났으면 다시 봐도 지우지 않는다(DB 저장 직전의 정상 요청일 수 있다)
        reconcileService.reconcile();
        clock.advance(Duration.ofSeconds(30));
        reconcileService.reconcile();
        assertThat(redis.opsForSet().isMember(users(couponId), "99")).isTrue();

        clock.advance(Duration.ofSeconds(30));
        reconcileService.reconcile();

        assertThat(redis.opsForSet().isMember(users(couponId), "99")).isFalse();
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
    }

    @Test
    @DisplayName("Redis에만 있던 회원이 확인 사이에 DB에 저장되면 지우지 않는다")
    void keepsWhenSavedMeanwhile() {
        Long couponId = createIssuingCoupon(5);
        redis.opsForSet().add(users(couponId), "99");
        reconcileService.reconcile();

        // 처음 본 뒤 DB 저장이 끝난 느린 정상 요청
        saveOnlyInDb(couponId, 99L);
        clock.advance(Duration.ofMinutes(2));
        reconcileService.reconcile();

        assertThat(redis.opsForSet().isMember(users(couponId), "99")).isTrue();
    }

    @Test
    @DisplayName("DB에만 있는 회원은 바로 Redis에 추가해 같은 회원이 다시 받지 못한다")
    void addsDbOnly() {
        Long couponId = createIssuingCoupon(5);
        saveOnlyInDb(couponId, 7L);

        reconcileService.reconcile();

        assertThat(redis.opsForSet().isMember(users(couponId), "7")).isTrue();
        assertThatThrownBy(() -> couponIssueService.issue(couponId, 7L, "user7@ticket.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_ALREADY_ISSUED);
    }

    @Test
    @DisplayName("Redis 총 수량·삭제 표시가 DB와 다르면 DB 값으로 맞춘다")
    void syncsMeta() {
        // 총 수량을 늘리다 DB가 롤백됐는데 되돌리기를 못 한 상태 + 삭제 표시가 남은 상태
        Long couponId = createIssuingCoupon(10);
        redis.opsForHash().put(meta(couponId), "total", "50");
        redis.opsForHash().put(meta(couponId), "deleted", "1");

        reconcileService.reconcile();

        assertThat(redis.opsForHash().get(meta(couponId), "total")).isEqualTo("10");
        assertThat(redis.opsForHash().get(meta(couponId), "deleted")).isEqualTo("0");
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
    }

    @Test
    @DisplayName("issued_quantity를 회원 쿠폰 수로 맞춘다")
    void syncsIssuedQuantity() {
        Long couponId = createIssuingCoupon(5);
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
        couponIssueService.issue(couponId, 2L, "user2@ticket.com");
        assertThat(issuedQuantity(couponId)).isZero();

        reconcileService.reconcile();

        assertThat(issuedQuantity(couponId)).isEqualTo(2);
    }

    @Test
    @DisplayName("회원 쿠폰 수가 총 수량을 넘으면 경보를 남기고 issued_quantity를 바꾸지 않으며, 다른 쿠폰은 계속 맞춘다")
    void overIssued() {
        Long over = createIssuingCoupon(1);
        couponIssueService.issue(over, 1L, "user1@ticket.com");
        saveOnlyInDb(over, 2L);
        Long normal = createIssuingCoupon(5);
        couponIssueService.issue(normal, 1L, "user1@ticket.com");

        Result result = reconcileService.reconcile();

        assertThat(result.overIssued()).isPositive();
        assertThat(issuedQuantity(over)).isZero();
        assertThat(issuedQuantity(normal)).isEqualTo(1);
    }

    @Test
    @DisplayName("발급 중단으로 종료 시각이 당겨지면 두 키의 유효 시간도 종료 + 1일로 다시 건다")
    void followsEndChange() {
        Long couponId = createIssuingCoupon(5);
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
        couponService.close(couponId);

        reconcileService.reconcile();

        long oneDay = Duration.ofDays(1).toSeconds();
        assertThat(redis.getExpire(meta(couponId))).isBetween(oneDay - 10, oneDay);
        assertThat(redis.getExpire(users(couponId))).isBetween(oneDay - 10, oneDay);
    }

    @Test
    @DisplayName("Redis 정보가 없는 쿠폰은 키를 만들지 않는다(다음 발급 때 DB에서 채운다)")
    void skipsNotLoaded() {
        Long couponId = createIssuingCoupon(5);
        couponIssueService.issue(couponId, 1L, "user1@ticket.com");
        redis.delete(List.of(meta(couponId), users(couponId)));

        reconcileService.reconcile();

        assertThat(redis.hasKey(meta(couponId))).isFalse();
        assertThat(redis.hasKey(users(couponId))).isFalse();
        assertThat(issuedQuantity(couponId)).isEqualTo(1);
    }

    @Test
    @DisplayName("끝난 지 하루가 지난 쿠폰은 대상에서 빠진다")
    void skipsLongEnded() {
        Long couponId = createIssuingCoupon(5);
        redis.opsForHash().put(meta(couponId), "total", "50");

        // 종료(지금 + 1일) + 1일이 지난 뒤
        clock.advance(Duration.ofDays(2).plusMinutes(1));
        reconcileService.reconcile();

        assertThat(redis.opsForHash().get(meta(couponId), "total")).isEqualTo("50");
    }
}
