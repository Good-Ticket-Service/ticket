package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 배치에 쓰는 회원 쿠폰 저장소 기능(findExpirableIds, expire)을 실제 PostgreSQL로 확인한다.
 * Note: 대상 고르기는 회원을 가리지 않고 테이블 전체를 본다. 다른 테스트의 쿠폰이 섞이지 않도록 어떤 테스트도 쓰지 않는
 * 과거 시각(2001년)에 발급하고, 이 테스트만 쓰는 회원 ID(940xxx)의 행을 테스트마다 지운다.
 */
class UserCouponExpiryPersistenceIntegrationTest extends ReservationIntegrationTest {

    /** 발급 기준 시각. 유효 일수가 7일이라 이때 받은 쿠폰의 만료 시각은 EXPIRES_AT이다. */
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2001, 3, 1, 12, 0);

    private static final LocalDateTime EXPIRES_AT = ISSUED_AT.plusDays(7);

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM p_user_coupons WHERE user_id BETWEEN 940000 AND 940999");
    }

    /** 유효 일수가 7일인 쿠폰을 issuedAt에 발급한다. 만료 시각은 issuedAt + 7일이다. */
    private UserCoupon issue(long userId, LocalDateTime issuedAt) {
        Coupon coupon = couponRepository.save(
                Coupon.create("만료 배치 쿠폰", 5_000, 100, issuedAt.minusDays(1), issuedAt.plusDays(30), 7, issuedAt));
        return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, issuedAt));
    }

    /** 한 트랜잭션에서 ID로 잠그고 읽어 바꾼다. 저장은 커밋 때 변경 감지가 한다. */
    private void change(Long userCouponId, Consumer<UserCoupon> change) {
        transactionTemplate.executeWithoutResult(status ->
                change.accept(userCouponRepository.lockById(userCouponId).orElseThrow()));
    }

    /** 만료 처리. UPDATE는 트랜잭션 안에서 실행해야 한다. */
    private int expireIds(List<Long> ids, LocalDateTime now) {
        return transactionTemplate.execute(status -> userCouponRepository.expire(ids, now, "SYSTEM"));
    }

    /** 엔티티를 거치지 않고 DB에 저장된 값을 읽는다. */
    private Map<String, Object> row(Long userCouponId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, updated_at, updated_by FROM p_user_coupons WHERE user_coupon_id = ?", userCouponId);
    }

    @Nested
    @DisplayName("만료 대상 고르기")
    class FindExpirable {

        @Test
        @DisplayName("만료 시각이 지났거나 정각인 AVAILABLE만 고르고, 기간이 남은 쿠폰·RESERVED·USED는 고르지 않는다")
        void onlyExpiredAvailable() {
            UserCoupon past = issue(940_001L, ISSUED_AT.minusDays(1));
            UserCoupon onTime = issue(940_002L, ISSUED_AT);
            issue(940_003L, ISSUED_AT.plusMinutes(1));
            UserCoupon reserved = issue(940_004L, ISSUED_AT.minusDays(1));
            change(reserved.getId(), uc -> uc.reserve(940_004L, 941_004L, ISSUED_AT));
            UserCoupon used = issue(940_005L, ISSUED_AT.minusDays(1));
            change(used.getId(), uc -> uc.reserve(940_005L, 941_005L, ISSUED_AT));
            change(used.getId(), uc -> uc.use(ISSUED_AT));

            // 만료 시각 정각부터 만료다(displayStatus와 같은 경계). 만료 시각이 이른 순으로 나온다
            assertThat(userCouponRepository.findExpirableIds(EXPIRES_AT, 10))
                    .containsExactly(past.getId(), onTime.getId());
        }

        @Test
        @DisplayName("limit개까지만 고르고, 만료 시각이 이른 것부터 고른다")
        void limit() {
            issue(940_011L, ISSUED_AT.minusDays(1));
            UserCoupon first = issue(940_012L, ISSUED_AT.minusDays(3));
            UserCoupon second = issue(940_013L, ISSUED_AT.minusDays(2));

            assertThat(userCouponRepository.findExpirableIds(EXPIRES_AT, 2))
                    .containsExactly(first.getId(), second.getId());
        }
    }

    @Nested
    @DisplayName("만료 처리")
    class Expire {

        @Test
        @DisplayName("고른 쿠폰을 EXPIRED로 바꾸고 updated_at·updated_by를 남긴다. 다시 고르면 나오지 않는다")
        void expire() {
            UserCoupon coupon = issue(940_021L, ISSUED_AT);
            LocalDateTime now = EXPIRES_AT.plusHours(1);

            int changed = expireIds(List.of(coupon.getId()), now);

            assertThat(changed).isEqualTo(1);
            assertThat(row(coupon.getId())).containsEntry("status", "EXPIRED").containsEntry("updated_by", "SYSTEM");
            assertThat(row(coupon.getId()).get("updated_at")).isEqualTo(Timestamp.valueOf(now));
            assertThat(userCouponRepository.findExpirableIds(now, 10)).doesNotContain(coupon.getId());
        }

        @Test
        @DisplayName("고른 뒤 예매에 잡히거나 아직 만료 전인 쿠폰은 건너뛰고, 바꾼 행 수만 돌려준다")
        void skipChanged() {
            UserCoupon expired = issue(940_031L, ISSUED_AT.minusDays(1));
            UserCoupon reservedAfterPick = issue(940_032L, ISSUED_AT.minusDays(1));
            UserCoupon notYet = issue(940_033L, ISSUED_AT.plusDays(1));
            List<Long> picked = userCouponRepository.findExpirableIds(EXPIRES_AT, 10);
            assertThat(picked).containsExactly(expired.getId(), reservedAfterPick.getId());

            // 고른 뒤 예매가 이 쿠폰을 잡았다(만료 시각 전에 잡았다고 가정한다)
            change(reservedAfterPick.getId(), uc -> uc.reserve(940_032L, 941_032L, ISSUED_AT));

            // 아직 만료 전인 쿠폰 ID가 섞여 들어와도 바꾸지 않는다
            int changed = expireIds(List.of(expired.getId(), reservedAfterPick.getId(), notYet.getId()), EXPIRES_AT);

            assertThat(changed).isEqualTo(1);
            assertThat(row(expired.getId())).containsEntry("status", "EXPIRED");
            assertThat(row(reservedAfterPick.getId())).containsEntry("status", "RESERVED");
            assertThat(row(notYet.getId())).containsEntry("status", "AVAILABLE");
        }

        @Test
        @DisplayName("빈 목록이면 쿼리 없이 0을 돌려준다")
        void empty() {
            assertThat(expireIds(List.of(), EXPIRES_AT)).isZero();
        }
    }
}
