package com.sparta.ticket.reservation.booking.support;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 쿠폰 테스트 데이터. 쿠폰 테이블에 직접 넣고 읽어, 예약이 부른 실제 쿠폰 구현의 결과를 호출 경로와 상관없이 확인한다. */
public class CouponFixture {

    private final JdbcTemplate jdbc;
    private final MutableClock clock;

    public CouponFixture(JdbcTemplate jdbc, MutableClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void reset() {
        jdbc.update("DELETE FROM p_user_coupons");
        jdbc.update("DELETE FROM p_coupons");
    }

    /** 이 회원에게 할인액이 amount인 쿠폰을 발급하고 회원 쿠폰 ID를 돌려준다. 유효 기간은 30일이다. */
    public Long issue(Long userId, int amount) {
        LocalDateTime now = clock.now();
        Long couponId = insert(
                "INSERT INTO p_coupons (name, discount_amount, total_quantity, issued_quantity, issue_start_at,"
                        + " issue_end_at, valid_days, created_at, created_by, updated_at, updated_by)"
                        + " VALUES ('test', ?, 100, 1, ?, ?, 30, ?, 'test', ?, 'test')",
                "coupon_id",
                amount,
                now.minusDays(1),
                now.plusDays(1),
                now,
                now);
        return insert(
                "INSERT INTO p_user_coupons (user_id, coupon_id, discount_amount, status, issued_at, expires_at,"
                        + " created_at, created_by, updated_at, updated_by)"
                        + " VALUES (?, ?, ?, 'AVAILABLE', ?, ?, ?, 'test', ?, 'test')",
                "user_coupon_id",
                userId,
                couponId,
                amount,
                now,
                now.plusDays(30),
                now,
                now);
    }

    public String statusOf(Long userCouponId) {
        return jdbc.queryForObject(
                "SELECT status FROM p_user_coupons WHERE user_coupon_id = ?", String.class, userCouponId);
    }

    public Long reservationOf(Long userCouponId) {
        return jdbc.queryForObject(
                "SELECT reservation_id FROM p_user_coupons WHERE user_coupon_id = ?", Long.class, userCouponId);
    }

    private Long insert(String sql, String keyColumn, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(sql, new String[] {keyColumn});
                    for (int i = 0; i < args.length; i++) {
                        Object arg = args[i];
                        statement.setObject(i + 1, arg instanceof LocalDateTime time ? Timestamp.valueOf(time) : arg);
                    }
                    return statement;
                },
                keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
