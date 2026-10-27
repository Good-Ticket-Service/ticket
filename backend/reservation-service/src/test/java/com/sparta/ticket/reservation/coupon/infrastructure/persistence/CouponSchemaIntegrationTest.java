package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** schema.sql의 p_coupons 제약이 설계(docs/coupon/tables.md 3절)대로 동작하는지 실제 PostgreSQL로 확인한다. */
class CouponSchemaIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Coupon saveCoupon() {
        return couponRepository.save(
                Coupon.create("오픈 기념 5,000원 할인", 5_000, 1_000, NOW.plusDays(1), NOW.plusDays(7), 7, NOW));
    }

    @Test
    @DisplayName("저장한 쿠폰을 다시 읽으면 같은 값이고 발급 수량은 0이다")
    void saveAndReload() {
        Coupon saved = saveCoupon();

        Coupon found = couponRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("오픈 기념 5,000원 할인");
        assertThat(found.getDiscountAmount()).isEqualTo(5_000);
        assertThat(found.getTotalQuantity()).isEqualTo(1_000);
        assertThat(found.getIssuedQuantity()).isZero();
        assertThat(found.getIssueStartAt()).isEqualTo(NOW.plusDays(1));
        assertThat(found.getIssueEndAt()).isEqualTo(NOW.plusDays(7));
        assertThat(found.getValidDays()).isEqualTo(7);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getCreatedBy()).isNotNull();
    }

    @Test
    @DisplayName("논리 삭제한 쿠폰은 조회되지 않는다")
    void softDeletedIsHidden() {
        Coupon saved = saveCoupon();
        saved.softDelete("master");
        couponRepository.save(saved);

        assertThat(couponRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("발급 수량이 총 수량을 넘으면 ck_p_coupons_quantity가 막는다")
    void issuedQuantityCannotExceedTotal() {
        Coupon saved = saveCoupon();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE p_coupons SET issued_quantity = total_quantity + 1 WHERE coupon_id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_coupons_quantity");
    }

    @Test
    @DisplayName("발급 시작 시각이 종료 시각보다 늦으면 ck_p_coupons_period가 막고, 같으면 허용한다")
    void periodOrder() {
        Coupon saved = saveCoupon();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE p_coupons SET issue_start_at = issue_end_at + INTERVAL '1 second' WHERE coupon_id = ?",
                        saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_p_coupons_period");

        int updated = jdbcTemplate.update(
                "UPDATE p_coupons SET issue_start_at = issue_end_at WHERE coupon_id = ?", saved.getId());
        assertThat(updated).isEqualTo(1);
    }
}
