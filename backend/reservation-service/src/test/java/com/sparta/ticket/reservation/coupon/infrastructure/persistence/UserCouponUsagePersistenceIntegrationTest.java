package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예매 연동(CouponUsage)에 쓰는 회원 쿠폰 저장소 기능을 실제 PostgreSQL로 확인한다.
 * 잠그고 읽은 회원 쿠폰을 엔티티 메서드로 바꾸면 커밋 때 변경 감지로 저장되고, 그 결과가 DB CHECK 제약을 지키는지 본다.
 * Note: 다른 테스트의 회원 쿠폰도 같은 테이블에 있어, 회원 ID와 예매 ID를 이 테스트만 쓰는 값(97xxxx)으로 둔다.
 */
class UserCouponUsagePersistenceIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 유효 일수가 7일인 쿠폰을 만들고 회원에게 NOW에 발급한다. 만료 시각은 NOW + 7일이다. */
    private UserCoupon issue(long userId) {
        Coupon coupon = couponRepository.save(
                Coupon.create("예매 연동 쿠폰", 5_000, 100, NOW.minusDays(1), NOW.plusDays(30), 7, NOW));
        return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, NOW));
    }

    /** 한 트랜잭션에서 ID로 잠그고 읽어 바꾼다. 저장은 커밋 때 변경 감지가 한다. */
    private void changeById(Long userCouponId, Consumer<UserCoupon> change) {
        transactionTemplate.executeWithoutResult(status ->
                change.accept(userCouponRepository.lockById(userCouponId).orElseThrow()));
    }

    /** 한 트랜잭션에서 예매 ID로 잠그고 읽어 바꾼다. */
    private void changeByReservation(Long reservationId, Consumer<UserCoupon> change) {
        transactionTemplate.executeWithoutResult(status -> change.accept(
                userCouponRepository.lockByReservationId(reservationId).orElseThrow()));
    }

    /** 엔티티를 거치지 않고 DB에 저장된 값을 읽는다. */
    private Map<String, Object> row(Long userCouponId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, used_at FROM p_user_coupons WHERE user_coupon_id = ?", userCouponId);
    }

    @Nested
    @DisplayName("잠그고 읽기")
    class Lock {

        @Test
        @DisplayName("ID로 잠그고 읽으면 회원 쿠폰이 나오고, 없는 ID면 비어 있다")
        void lockById() {
            UserCoupon issued = issue(970_001L);

            transactionTemplate.executeWithoutResult(status -> {
                assertThat(userCouponRepository.lockById(issued.getId()))
                        .get()
                        .extracting(UserCoupon::getUserId)
                        .isEqualTo(970_001L);
                assertThat(userCouponRepository.lockById(-1L)).isEmpty();
            });
        }

        @Test
        @DisplayName("예매 ID로 잠그고 읽으면 RESERVED·USED 쿠폰이 나오고, 돌려준 뒤나 잡힌 쿠폰이 없으면 비어 있다")
        void lockByReservationId() {
            UserCoupon issued = issue(970_011L);
            changeById(issued.getId(), uc -> uc.reserve(970_011L, 971_011L, NOW));

            // RESERVED
            transactionTemplate.executeWithoutResult(
                    status -> assertThat(userCouponRepository.lockByReservationId(971_011L))
                            .get()
                            .extracting(UserCoupon::getId)
                            .isEqualTo(issued.getId()));

            // USED
            changeByReservation(971_011L, uc -> uc.use(NOW));
            transactionTemplate.executeWithoutResult(
                    status -> assertThat(userCouponRepository.lockByReservationId(971_011L))
                            .isPresent());

            // 돌려준 뒤에는 예매 ID가 지워져 찾을 수 없다
            changeByReservation(971_011L, uc -> uc.release(NOW));
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(userCouponRepository.lockByReservationId(971_011L)).isEmpty();
                assertThat(userCouponRepository.lockByReservationId(-1L)).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("상태 전이 저장")
    class Transitions {

        @Test
        @DisplayName("reserve → use → release를 차례로 저장하면 DB 값이 상태 규칙(CHECK)대로 바뀐다")
        void reserveUseRelease() {
            UserCoupon issued = issue(970_021L);

            changeById(issued.getId(), uc -> uc.reserve(970_021L, 971_021L, NOW));
            assertThat(row(issued.getId()))
                    .containsEntry("status", "RESERVED")
                    .containsEntry("reservation_id", 971_021L)
                    .containsEntry("used_at", null);

            changeByReservation(971_021L, uc -> uc.use(NOW.plusHours(1)));
            assertThat(row(issued.getId())).containsEntry("status", "USED").containsEntry("reservation_id", 971_021L);
            assertThat(row(issued.getId()).get("used_at")).isNotNull();

            changeByReservation(971_021L, uc -> uc.release(NOW.plusDays(1)));
            assertThat(row(issued.getId()))
                    .containsEntry("status", "AVAILABLE")
                    .containsEntry("reservation_id", null)
                    .containsEntry("used_at", null);
        }

        @Test
        @DisplayName("만료 시각이 지난 뒤 돌려주면 EXPIRED로 저장된다")
        void releaseAsExpired() {
            UserCoupon issued = issue(970_031L);
            changeById(issued.getId(), uc -> uc.reserve(970_031L, 971_031L, NOW));

            changeByReservation(971_031L, uc -> uc.release(NOW.plusDays(8)));

            assertThat(row(issued.getId())).containsEntry("status", "EXPIRED").containsEntry("reservation_id", null);
        }

        @Test
        @DisplayName("한 예매에 쿠폰 두 장이 잡히면 uk_p_user_coupons_reservation이 막는다")
        void oneCouponPerReservation() {
            UserCoupon first = issue(970_041L);
            UserCoupon second = issue(970_041L);
            changeById(first.getId(), uc -> uc.reserve(970_041L, 971_041L, NOW));

            // 서비스의 사전 확인(existsOtherOnReservation)을 건너뛰어도 DB가 마지막으로 막는다
            assertThatThrownBy(() -> changeById(second.getId(), uc -> uc.reserve(970_041L, 971_041L, NOW)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_p_user_coupons_reservation");
            assertThat(row(second.getId())).containsEntry("status", "AVAILABLE");
        }
    }

    @Nested
    @DisplayName("예매의 다른 쿠폰 확인")
    class ExistsOther {

        @Test
        @DisplayName("이 예매에 다른 쿠폰이 잡혀 있으면 true, 같은 쿠폰이거나 잡힌 쿠폰이 없으면 false다")
        void existsOther() {
            UserCoupon reserved = issue(970_051L);
            UserCoupon another = issue(970_051L);
            changeById(reserved.getId(), uc -> uc.reserve(970_051L, 971_051L, NOW));

            assertThat(userCouponRepository.existsOtherOnReservation(971_051L, another.getId()))
                    .isTrue();
            assertThat(userCouponRepository.existsOtherOnReservation(971_051L, reserved.getId()))
                    .isFalse();
            assertThat(userCouponRepository.existsOtherOnReservation(971_052L, another.getId()))
                    .isFalse();
        }
    }
}
