package com.sparta.ticket.reservation.coupon.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.DeleteMark;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.IssueDecision;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.TotalChange;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 쿠폰 발급 판단 Lua 스크립트를 실제 Redis로 확인한다. 쿠폰 ID는 테스트마다 새로 써서 다른 테스트의 키와 섞이지 않게 한다. */
class RedisCouponIssueGateIntegrationTest extends ReservationIntegrationTest {

    /** DB 쿠폰과 겹치지 않는 큰 ID부터 쓴다. 이 테스트는 DB를 쓰지 않는다. */
    private static final AtomicLong IDS = new AtomicLong(9_000_000_000L + System.nanoTime() % 1_000_000);

    /** 테스트 중에 지워지지 않을 만큼 긴 유효 시간. 유효 시간 자체를 보는 테스트만 따로 정한다. */
    private static final Duration FAR = Duration.ofDays(1);

    @Autowired
    private CouponIssueGate gate;

    @Autowired
    private StringRedisTemplate redis;

    /** 총 수량으로 정보를 만든 새 쿠폰 ID. */
    private long newCoupon(int totalQuantity) {
        long id = IDS.incrementAndGet();
        gate.reset(id, totalQuantity, FAR);
        return id;
    }

    @Nested
    @DisplayName("발급 판단")
    class TryIssue {

        @Test
        @DisplayName("남아 있으면 기록하고, 총 수량만큼 받으면 다음은 소진이다")
        void issuesUntilSoldOut() {
            long id = newCoupon(2);

            assertThat(gate.tryIssue(id, 1L, true)).isEqualTo(IssueDecision.ISSUED);
            assertThat(gate.tryIssue(id, 2L, true)).isEqualTo(IssueDecision.ISSUED);
            assertThat(gate.tryIssue(id, 3L, true)).isEqualTo(IssueDecision.SOLD_OUT);
            assertThat(redis.opsForSet().members(CouponKeys.users(id))).containsExactlyInAnyOrder("1", "2");
        }

        @Test
        @DisplayName("이미 받은 회원은 기간 밖·소진보다 먼저 이미 받음이다(오류 우선순위)")
        void alreadyIssuedFirst() {
            long id = newCoupon(1);
            gate.tryIssue(id, 1L, true);

            assertThat(gate.tryIssue(id, 1L, false)).isEqualTo(IssueDecision.ALREADY_ISSUED);
        }

        @Test
        @DisplayName("기간 밖이면 기록하지 않고, 삭제 표시가 있으면 기간보다 먼저 삭제됨이다")
        void periodAndDeleted() {
            long id = newCoupon(10);

            assertThat(gate.tryIssue(id, 1L, false)).isEqualTo(IssueDecision.PERIOD_CLOSED);
            gate.markDeleted(id);
            assertThat(gate.tryIssue(id, 1L, false)).isEqualTo(IssueDecision.DELETED);
            assertThat(redis.opsForSet().size(CouponKeys.users(id))).isZero();
        }

        @Test
        @DisplayName("정보 키가 없으면 판단하지 않고 정보 없음이다")
        void notLoaded() {
            long id = IDS.incrementAndGet();

            assertThat(gate.tryIssue(id, 1L, true)).isEqualTo(IssueDecision.NOT_LOADED);
            assertThat(gate.isLoaded(id)).isFalse();
        }

        @Test
        @DisplayName("되돌리면 회원이 빠져 그 자리가 다시 빈다")
        void cancel() {
            long id = newCoupon(1);
            gate.tryIssue(id, 1L, true);

            gate.cancel(id, 1L);

            assertThat(gate.tryIssue(id, 2L, true)).isEqualTo(IssueDecision.ISSUED);
        }
    }

    @Nested
    @DisplayName("채우기·새로 만들기")
    class Load {

        @Test
        @DisplayName("정보가 없으면 DB 값으로 채우고, 이미 있으면 그사이 기록된 회원을 덮지 않는다")
        void loadOnlyIfAbsent() {
            long id = IDS.incrementAndGet();
            gate.loadIfAbsent(id, 3, false, List.of(1L, 2L), FAR);
            gate.tryIssue(id, 3L, true);

            // 늦게 온 채우기가 옛 목록(회원 1, 2)으로 덮으면 회원 3이 사라진다
            gate.loadIfAbsent(id, 3, false, List.of(1L, 2L), FAR);

            assertThat(redis.opsForSet().members(CouponKeys.users(id))).containsExactlyInAnyOrder("1", "2", "3");
            assertThat(gate.tryIssue(id, 4L, true)).isEqualTo(IssueDecision.SOLD_OUT);
        }

        @Test
        @DisplayName("SADD 한 번의 인자 수를 넘는 회원 목록도 모두 채운다")
        void loadManyUsers() {
            long id = IDS.incrementAndGet();
            List<Long> users = LongStream.rangeClosed(1, 2_500).boxed().toList();

            gate.loadIfAbsent(id, 3_000, false, users, FAR);

            assertThat(redis.opsForSet().size(CouponKeys.users(id))).isEqualTo(2_500);
        }

        @Test
        @DisplayName("새로 만들면 같은 ID로 남은 이전 회원을 지우고 0명으로 시작한다")
        void resetClearsOldUsers() {
            long id = newCoupon(1);
            gate.tryIssue(id, 1L, true);

            gate.reset(id, 1, FAR);

            assertThat(gate.tryIssue(id, 1L, true)).isEqualTo(IssueDecision.ISSUED);
        }

        @Test
        @DisplayName("새로 만들기와 다시 채우기는 정해 준 시간이 지나면 키가 지워지도록 유효 시간을 건다(#323)")
        void setsTtl() {
            long created = IDS.incrementAndGet();
            long loaded = IDS.incrementAndGet();

            gate.reset(created, 10, Duration.ofHours(1));
            gate.loadIfAbsent(loaded, 10, false, List.of(1L), Duration.ofHours(1));

            assertThat(redis.getExpire(CouponKeys.meta(created))).isBetween(3_590L, 3_600L);
            assertThat(redis.getExpire(CouponKeys.meta(loaded))).isBetween(3_590L, 3_600L);
            assertThat(redis.getExpire(CouponKeys.users(loaded))).isBetween(3_590L, 3_600L);
        }
    }

    @Nested
    @DisplayName("총 수량 변경")
    class ChangeTotal {

        @Test
        @DisplayName("받은 회원 수 이상이면 바꾸고, 적으면 바꾸지 않는다")
        void belowIssued() {
            long id = newCoupon(10);
            gate.tryIssue(id, 1L, true);
            gate.tryIssue(id, 2L, true);

            assertThat(gate.changeTotal(id, 1)).isEqualTo(TotalChange.BELOW_ISSUED);
            assertThat(gate.changeTotal(id, 2)).isEqualTo(TotalChange.CHANGED);
            assertThat(gate.tryIssue(id, 3L, true)).isEqualTo(IssueDecision.SOLD_OUT);
        }

        @Test
        @DisplayName("되돌리기는 아직 바꾼 값일 때만 한다")
        void restoreOnlyIfUnchanged() {
            long id = newCoupon(10);
            gate.changeTotal(id, 20);

            gate.restoreTotal(id, 30, 10);
            assertThat(redis.opsForHash().get(CouponKeys.meta(id), "total")).isEqualTo("20");

            gate.restoreTotal(id, 20, 10);
            assertThat(redis.opsForHash().get(CouponKeys.meta(id), "total")).isEqualTo("10");
        }

        @Test
        @DisplayName("정보가 없으면 바꾸지 않고 정보 없음이다")
        void notLoaded() {
            assertThat(gate.changeTotal(IDS.incrementAndGet(), 10)).isEqualTo(TotalChange.NOT_LOADED);
        }
    }

    @Nested
    @DisplayName("삭제 표시")
    class MarkDeleted {

        @Test
        @DisplayName("받은 회원이 있으면 표시하지 않는다")
        void issuedExists() {
            long id = newCoupon(10);
            gate.tryIssue(id, 1L, true);

            assertThat(gate.markDeleted(id)).isEqualTo(DeleteMark.ISSUED_EXISTS);
            assertThat(gate.tryIssue(id, 2L, true)).isEqualTo(IssueDecision.ISSUED);
        }

        @Test
        @DisplayName("표시를 지우면 다시 발급되고, 정보가 없으면 지우기가 정보 키를 만들지 않는다")
        void unmark() {
            long id = newCoupon(10);
            gate.markDeleted(id);

            gate.unmarkDeleted(id);
            assertThat(gate.tryIssue(id, 1L, true)).isEqualTo(IssueDecision.ISSUED);

            long absent = IDS.incrementAndGet();
            gate.unmarkDeleted(absent);
            assertThat(gate.isLoaded(absent)).isFalse();
        }
    }

    @Test
    @DisplayName("받은 회원 수는 정보가 있는 쿠폰만 돌려준다")
    void issuedCounts() {
        long two = newCoupon(10);
        long zero = newCoupon(10);
        long absent = IDS.incrementAndGet();
        gate.tryIssue(two, 1L, true);
        gate.tryIssue(two, 2L, true);

        assertThat(gate.issuedCounts(List.of(two, zero, absent)))
                .containsEntry(two, 2)
                .containsEntry(zero, 0)
                .doesNotContainKey(absent);
    }
}
