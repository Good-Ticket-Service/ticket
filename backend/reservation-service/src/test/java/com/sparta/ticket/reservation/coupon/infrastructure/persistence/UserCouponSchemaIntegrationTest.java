package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** coupon.sql의 p_user_coupons가 Entity와 맞고, 제약이 명세(docs/tables.md p_user_coupons)대로 동작하는지 실제 PostgreSQL로 확인한다. */
class UserCouponSchemaIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Coupon saveCoupon() {
        return couponRepository.save(
                Coupon.create("오픈 기념 5,000원 할인", 5_000, 1_000, NOW.minusDays(1), NOW.plusDays(7), 7, NOW));
    }

    /** 저장소는 아직 없어 EntityManager로 저장한다. 감사 컬럼은 Entity 저장 때 채워진다. */
    private UserCoupon saveUserCoupon(Long userId, Coupon coupon) {
        return transactionTemplate.execute(status -> {
            UserCoupon userCoupon = UserCoupon.issue(userId, coupon, NOW);
            entityManager.persist(userCoupon);
            return userCoupon;
        });
    }

    /**
     * 상태·예매 ID·사용 시각만 바꿔 CHECK 제약을 확인한다.
     * Note: 엔티티 메서드(reserve·use·release)는 규칙을 지킨 값만 만들므로, 제약을 어기는 값을 넣어 보려고 SQL로 직접 바꾼다.
     */
    private int updateState(Long userCouponId, String status, Long reservationId, LocalDateTime usedAt) {
        return jdbcTemplate.update(
                "UPDATE p_user_coupons SET status = ?, reservation_id = ?, used_at = ? WHERE user_coupon_id = ?",
                status,
                reservationId,
                usedAt,
                userCouponId);
    }

    @Test
    @DisplayName("저장한 회원 쿠폰을 다시 읽으면 같은 값이다")
    void saveAndReload() {
        Coupon coupon = saveCoupon();
        UserCoupon saved = saveUserCoupon(1L, coupon);

        UserCoupon found = transactionTemplate.execute(status -> entityManager.find(UserCoupon.class, saved.getId()));

        assertThat(found.getUserId()).isEqualTo(1L);
        assertThat(found.getCouponId()).isEqualTo(coupon.getId());
        assertThat(found.getDiscountAmount()).isEqualTo(5_000);
        assertThat(found.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
        assertThat(found.getIssuedAt()).isEqualTo(NOW);
        assertThat(found.getExpiresAt()).isEqualTo(NOW.plusDays(7));
        assertThat(found.getReservationId()).isNull();
        assertThat(found.getUsedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 회원이 같은 쿠폰을 두 번 받으면 uk_p_user_coupons_user_coupon이 막는다")
    void onePerUser() {
        Coupon coupon = saveCoupon();
        saveUserCoupon(1L, coupon);

        // Note: EntityManager를 직접 쓰면 Spring의 예외 변환을 거치지 않아 Hibernate 예외(PersistenceException)가 그대로 나온다.
        assertThatThrownBy(() -> saveUserCoupon(1L, coupon))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("uk_p_user_coupons_user_coupon");
    }

    @Test
    @DisplayName("다른 회원은 같은 쿠폰을 받을 수 있다")
    void otherUserCanIssue() {
        Coupon coupon = saveCoupon();
        saveUserCoupon(1L, coupon);

        UserCoupon other = saveUserCoupon(2L, coupon);

        assertThat(other.getId()).isNotNull();
    }

    @Test
    @DisplayName("없는 쿠폰을 가리키면 FK가 막는다")
    void couponMustExist() {
        Coupon coupon = saveCoupon();
        UserCoupon saved = saveUserCoupon(1L, coupon);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE p_user_coupons SET coupon_id = ? WHERE user_coupon_id = ?", -1L, saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("RESERVED·USED는 예매 ID가 있어야 하고, AVAILABLE은 예매 ID가 없어야 한다(ck_p_user_coupons_reservation)")
    void reservationMatchesStatus() {
        UserCoupon saved = saveUserCoupon(1L, saveCoupon());

        assertThatThrownBy(() -> updateState(saved.getId(), "RESERVED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_user_coupons_reservation");
        assertThatThrownBy(() -> updateState(saved.getId(), "AVAILABLE", 100L, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_user_coupons_reservation");
        assertThat(updateState(saved.getId(), "RESERVED", 100L, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("USED만 사용 시각이 있다(ck_p_user_coupons_used)")
    void usedAtMatchesStatus() {
        UserCoupon saved = saveUserCoupon(1L, saveCoupon());

        assertThatThrownBy(() -> updateState(saved.getId(), "USED", 200L, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_user_coupons_used");
        assertThatThrownBy(() -> updateState(saved.getId(), "RESERVED", 200L, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_user_coupons_used");
        assertThat(updateState(saved.getId(), "USED", 200L, NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 예매에는 쿠폰을 한 장만 잡는다(uk_p_user_coupons_reservation)")
    void oneCouponPerReservation() {
        Coupon coupon = saveCoupon();
        UserCoupon first = saveUserCoupon(1L, coupon);
        UserCoupon second = saveUserCoupon(2L, coupon);
        updateState(first.getId(), "RESERVED", 300L, null);

        assertThatThrownBy(() -> updateState(second.getId(), "RESERVED", 300L, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_p_user_coupons_reservation");
    }

    @Test
    @DisplayName("정의하지 않은 상태와 0 이하의 할인액은 막는다")
    void statusAndDiscount() {
        UserCoupon saved = saveUserCoupon(1L, saveCoupon());

        assertThatThrownBy(() -> updateState(saved.getId(), "LOST", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_user_coupons_status");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE p_user_coupons SET discount_amount = 0 WHERE user_coupon_id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_user_coupons_discount");
    }
}
