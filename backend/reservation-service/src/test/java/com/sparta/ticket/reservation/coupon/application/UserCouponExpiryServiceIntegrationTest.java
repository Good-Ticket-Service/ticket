package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 만료 배치 서비스(UserCouponExpiryService)를 실제 PostgreSQL로 확인한다.
 * Note: 배치는 회원을 가리지 않고 테이블 전체를 본다. 다른 테스트의 쿠폰이 섞이지 않도록 공용 시계를 어떤 테스트도 쓰지 않는
 * 과거 시각(2001년)으로 옮겨 실행하고, 끝나면 원래 시각으로 돌린다. 이 테스트만 쓰는 회원 ID(942xxx)의 행은 테스트마다 지운다.
 */
class UserCouponExpiryServiceIntegrationTest extends ReservationIntegrationTest {

    /** 발급 기준 시각. 유효 일수가 7일이라 이때 받은 쿠폰의 만료 시각은 EXPIRES_AT이다. */
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2001, 6, 1, 12, 0);

    private static final LocalDateTime EXPIRES_AT = ISSUED_AT.plusDays(7);

    private static final Duration ENOUGH = Duration.ofSeconds(30);

    @Autowired
    private UserCouponExpiryService expiryService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private LocalDateTime originalNow;

    @BeforeEach
    void moveClock() {
        originalNow = clock.now();
        clock.set(EXPIRES_AT);
    }

    @AfterEach
    void restore() {
        clock.set(originalNow);
        jdbcTemplate.update("DELETE FROM p_user_coupons WHERE user_id BETWEEN 942000 AND 942999");
    }

    /** 유효 일수가 7일인 쿠폰을 issuedAt에 발급한다. 만료 시각은 issuedAt + 7일이다. */
    private UserCoupon issue(long userId, LocalDateTime issuedAt) {
        Coupon coupon = couponRepository.save(
                Coupon.create("만료 배치 쿠폰", 5_000, 100, issuedAt.minusDays(1), issuedAt.plusDays(30), 7, issuedAt));
        return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, issuedAt));
    }

    private String status(Long userCouponId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_user_coupons WHERE user_coupon_id = ?", String.class, userCouponId);
    }

    @Test
    @DisplayName("만료 시각이 지난 쿠폰을 묶음 여러 개로 나눠 모두 EXPIRED로 바꾸고, 기간이 남은 쿠폰은 그대로 둔다")
    void expireInBatches() {
        List<UserCoupon> expired = List.of(
                issue(942_001L, ISSUED_AT.minusDays(4)),
                issue(942_002L, ISSUED_AT.minusDays(3)),
                issue(942_003L, ISSUED_AT.minusDays(2)),
                issue(942_004L, ISSUED_AT.minusDays(1)),
                issue(942_005L, ISSUED_AT));
        UserCoupon notYet = issue(942_006L, ISSUED_AT.plusDays(1));

        // 2개씩 세 묶음(2, 2, 1)으로 처리한다
        assertThat(expiryService.expireDue(2, ENOUGH)).isEqualTo(5);

        assertThat(expired).allSatisfy(uc -> assertThat(status(uc.getId())).isEqualTo("EXPIRED"));
        assertThat(status(notYet.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("시간을 다 쓰면 묶음 하나만 처리하고 남은 건은 다음 실행이 처리한다")
    void stopWhenTimeBudgetExceeded() {
        issue(942_011L, ISSUED_AT.minusDays(3));
        issue(942_012L, ISSUED_AT.minusDays(2));
        issue(942_013L, ISSUED_AT.minusDays(1));

        // 첫 묶음을 처리한 뒤 시간이 지나 멈춘다
        assertThat(expiryService.expireDue(2, Duration.ZERO)).isEqualTo(2);
        // 다음 실행이 남은 하나를 처리한다
        assertThat(expiryService.expireDue(2, Duration.ZERO)).isEqualTo(1);
        assertThat(expiryService.expireDue(2, Duration.ZERO)).isZero();
    }

    @Test
    @DisplayName("바꿀 쿠폰이 없으면 0이다")
    void nothingToExpire() {
        issue(942_021L, ISSUED_AT.plusDays(1));

        assertThat(expiryService.expireDue(10, ENOUGH)).isZero();
    }

    @Test
    @DisplayName("배치 전후로 내 쿠폰 상태별 목록과 보여 주는 상태가 같다(판단은 저장값이 아니라 만료 시각으로 한다)")
    void sameViewBeforeAndAfter() {
        long userId = 942_031L;
        UserCoupon expired = issue(userId, ISSUED_AT.minusDays(1));
        UserCoupon available = issue(userId, ISSUED_AT.plusDays(1));
        LocalDateTime now = clock.now();

        List<Long> expiredBefore = mine(userId, UserCouponStatus.EXPIRED, now);
        List<Long> availableBefore = mine(userId, UserCouponStatus.AVAILABLE, now);
        UserCouponStatus shownBefore = shown(expired.getId(), now);

        expiryService.expireDue(10, ENOUGH);

        assertThat(expiredBefore).containsExactly(expired.getId());
        assertThat(availableBefore).containsExactly(available.getId());
        assertThat(mine(userId, UserCouponStatus.EXPIRED, now)).isEqualTo(expiredBefore);
        assertThat(mine(userId, UserCouponStatus.AVAILABLE, now)).isEqualTo(availableBefore);
        assertThat(shownBefore).isEqualTo(UserCouponStatus.EXPIRED);
        assertThat(shown(expired.getId(), now)).isEqualTo(UserCouponStatus.EXPIRED);
        // 저장값만 바뀌었다
        assertThat(status(expired.getId())).isEqualTo("EXPIRED");
    }

    private List<Long> mine(long userId, UserCouponStatus status, LocalDateTime now) {
        return userCouponRepository.findMine(userId, status, now, PageRequest.of(0, 10)).stream()
                .map(UserCoupon::getId)
                .toList();
    }

    private UserCouponStatus shown(Long userCouponId, LocalDateTime now) {
        return userCouponRepository.findMine(942_031L, null, now, PageRequest.of(0, 10)).stream()
                .filter(uc -> uc.getId().equals(userCouponId))
                .findFirst()
                .orElseThrow()
                .displayStatus(now);
    }
}
