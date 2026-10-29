package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 쿠폰 대역. #125 계약대로 회원 쿠폰 테이블(p_user_coupons)을 바꾼다. coupon 모듈의 실제 구현처럼 호출한 쪽 트랜잭션에
 * 합류하므로, 예약 전이가 롤백되면 쿠폰도 롤백되는지와 예약 하나에 쿠폰 하나라는 UK를 실제 DB로 확인할 수 있다.
 */
public class TableCouponPort implements CouponPort {

    private final JdbcTemplate jdbc;
    private final MutableClock clock;

    public TableCouponPort(JdbcTemplate jdbc, MutableClock clock) {
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

    @Override
    public long reserve(Long userCouponId, Long userId, Long reservationId) {
        LocalDateTime now = clock.now();
        int updated = jdbc.update(
                "UPDATE p_user_coupons SET status = 'RESERVED', reservation_id = ?, updated_at = ?, updated_by = 'test'"
                        + " WHERE user_coupon_id = ? AND user_id = ? AND status = 'AVAILABLE' AND expires_at > ?",
                reservationId,
                now,
                userCouponId,
                userId,
                now);
        Map<String, Object> row = jdbc
                .queryForList(
                        "SELECT status, reservation_id, discount_amount FROM p_user_coupons WHERE user_coupon_id = ?",
                        userCouponId)
                .stream()
                .findFirst()
                .orElse(null);
        boolean reservedHere = row != null
                && "RESERVED".equals(row.get("status"))
                && Objects.equals(((Number) row.get("reservation_id")).longValue(), reservationId);
        if (updated == 0 && !reservedHere) {
            throw new BusinessException(ReservationErrorCode.COUPON_NOT_AVAILABLE);
        }
        return ((Number) row.get("discount_amount")).longValue();
    }

    @Override
    public void use(Long reservationId) {
        jdbc.update(
                "UPDATE p_user_coupons SET status = 'USED', used_at = ?, updated_at = ?, updated_by = 'test'"
                        + " WHERE reservation_id = ? AND status = 'RESERVED'",
                clock.now(),
                clock.now(),
                reservationId);
    }

    @Override
    public void release(Long reservationId) {
        LocalDateTime now = clock.now();
        jdbc.update(
                "UPDATE p_user_coupons SET status = CASE WHEN expires_at > ? THEN 'AVAILABLE' ELSE 'EXPIRED' END,"
                        + " reservation_id = NULL, used_at = NULL, updated_at = ?, updated_by = 'test'"
                        + " WHERE reservation_id = ?",
                now,
                now,
                reservationId);
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
