package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조회에 쓰는 저장소 기능(발급 상태별 쿠폰 목록, 받은 쿠폰 ID, 내 쿠폰 상태별 목록)을 실제 PostgreSQL로 확인한다.
 * Note: 다른 테스트가 만든 쿠폰도 같은 테이블에 남아 있다. 그래서 결과 전체가 아니라 이 테스트가 만든 쿠폰이
 * 들어 있는지·빠졌는지로 확인하고, 기준 시각을 다른 테스트와 겹치지 않는 먼 미래(2030년)로 둔다.
 */
class CouponQueryPersistenceIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);
    /** 다른 테스트 쿠폰이 섞여도 이 테스트 쿠폰이 한 페이지에 다 들어오도록 크게 잡는다. */
    private static final Pageable ALL = PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 발급 기간을 정해 쿠폰을 만든다. 생성 시각은 종료 시각보다 앞이어야 해서 기간 시작보다 하루 이르게 둔다. */
    private Coupon saveCoupon(LocalDateTime start, LocalDateTime end) {
        return couponRepository.save(Coupon.create("쿠폰", 5_000, 100, start, end, 7, start.minusDays(1)));
    }

    private List<Long> ids(Page<?> page) {
        return page.getContent().stream()
                .map(item -> item instanceof Coupon coupon ? coupon.getId() : ((UserCoupon) item).getId())
                .toList();
    }

    @Nested
    @DisplayName("발급 상태별 쿠폰 목록")
    class SearchByIssueStatus {

        private List<Long> search(CouponIssueStatus status, Pageable pageable) {
            return ids(couponRepository.searchByIssueStatus(status, NOW, pageable));
        }

        @Test
        @DisplayName("시작 전·발급 중·종료를 기간으로 나누고, 시작 정각은 발급 중, 종료 정각은 종료다")
        void splitsByPeriod() {
            Coupon upcoming = saveCoupon(NOW.plusDays(1), NOW.plusDays(5));
            Coupon startsNow = saveCoupon(NOW, NOW.plusDays(5));
            Coupon issuing = saveCoupon(NOW.minusDays(1), NOW.plusDays(5));
            Coupon endsNow = saveCoupon(NOW.minusDays(2), NOW);
            Coupon ended = saveCoupon(NOW.minusDays(5), NOW.minusDays(1));

            assertThat(search(CouponIssueStatus.UPCOMING, ALL))
                    .contains(upcoming.getId())
                    .doesNotContain(startsNow.getId(), issuing.getId(), endsNow.getId(), ended.getId());
            assertThat(search(CouponIssueStatus.ISSUING, ALL))
                    .contains(startsNow.getId(), issuing.getId())
                    .doesNotContain(upcoming.getId(), endsNow.getId(), ended.getId());
            assertThat(search(CouponIssueStatus.ENDED, ALL))
                    .contains(endsNow.getId(), ended.getId())
                    .doesNotContain(upcoming.getId(), startsNow.getId(), issuing.getId());
        }

        @Test
        @DisplayName("삭제된 쿠폰은 목록에서 빠진다")
        void excludesDeleted() {
            Coupon deleted = saveCoupon(NOW.minusDays(1), NOW.plusDays(5));
            transactionTemplate.executeWithoutResult(status ->
                    couponRepository.lockById(deleted.getId()).orElseThrow().delete("master"));

            assertThat(search(CouponIssueStatus.ISSUING, ALL)).doesNotContain(deleted.getId());
        }

        @Test
        @DisplayName("Pageable의 정렬을 따른다(issueEndAt 오름차순)")
        void followsSort() {
            Coupon later = saveCoupon(NOW.minusDays(1), NOW.plusDays(9));
            Coupon sooner = saveCoupon(NOW.minusDays(1), NOW.plusDays(3));

            List<Long> result = search(
                    CouponIssueStatus.ISSUING, PageRequest.of(0, 10_000, Sort.by(Sort.Direction.ASC, "issueEndAt")));

            // 종료가 이른 쿠폰이 먼저 온다
            assertThat(result.indexOf(sooner.getId())).isLessThan(result.indexOf(later.getId()));
        }

        @Test
        @DisplayName("정렬 값이 같으면 ID로 순서를 정하고, 응답 페이지의 정렬은 요청한 정렬 그대로다")
        void tieBreaksById() {
            // 종료 시각이 모두 같은 쿠폰 세 개. 다른 테스트와 겹치지 않는 종료 시각을 쓴다
            LocalDateTime sameEnd = NOW.plusDays(17);
            List<Long> created = List.of(
                    saveCoupon(NOW.minusDays(1), sameEnd).getId(),
                    saveCoupon(NOW.minusDays(1), sameEnd).getId(),
                    saveCoupon(NOW.minusDays(1), sameEnd).getId());

            for (Sort.Direction direction : Sort.Direction.values()) {
                Sort byEnd = Sort.by(direction, "issueEndAt");
                Page<Coupon> page = couponRepository.searchByIssueStatus(
                        CouponIssueStatus.ISSUING, NOW, PageRequest.of(0, 10_000, byEnd));
                List<Long> ours = ids(page).stream().filter(created::contains).toList();

                // 같은 종료 시각끼리는 정렬 방향과 같은 방향의 ID 순서다
                List<Long> expected = new ArrayList<>(created);
                if (direction.isDescending()) {
                    Collections.reverse(expected);
                }
                assertThat(ours).isEqualTo(expected);
                // 보조 키는 조회에만 쓰고 응답 정렬(PageResponse.sort)에는 드러내지 않는다
                assertThat(page.getSort()).isEqualTo(byEnd);
            }
        }
    }

    @Nested
    @DisplayName("받은 쿠폰 ID")
    class FindIssuedCouponIds {

        @Test
        @DisplayName("주어진 쿠폰 가운데 이 회원이 받은 쿠폰의 ID만 돌려준다")
        void onlyIssuedByUser() {
            Coupon issued = saveCoupon(NOW.minusDays(1), NOW.plusDays(5));
            Coupon notIssued = saveCoupon(NOW.minusDays(1), NOW.plusDays(5));
            Coupon issuedByOther = saveCoupon(NOW.minusDays(1), NOW.plusDays(5));
            userCouponRepository.saveIssued(UserCoupon.issue(920_001L, issued, NOW));
            userCouponRepository.saveIssued(UserCoupon.issue(920_002L, issuedByOther, NOW));

            assertThat(userCouponRepository.findIssuedCouponIds(
                            920_001L, List.of(issued.getId(), notIssued.getId(), issuedByOther.getId())))
                    .containsExactly(issued.getId());
        }

        @Test
        @DisplayName("쿠폰 ID가 비어 있으면 조회 없이 빈 집합이다")
        void emptyIds() {
            assertThat(userCouponRepository.findIssuedCouponIds(920_001L, List.of()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("내 쿠폰 상태별 목록")
    class FindMine {

        private static final long USER_ID = 930_001L;

        /** 쿠폰을 하나 만들어 issuedAt에 발급한다. 유효 일수가 7일이라 만료 시각은 issuedAt + 7일이다. */
        private UserCoupon issueAt(long userId, LocalDateTime issuedAt) {
            Coupon coupon = saveCoupon(issuedAt.minusDays(1), issuedAt.plusDays(30));
            return userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, issuedAt));
        }

        /** 예매 연동(CouponUsage) 전이라 상태 전이 메서드가 없어 SQL로 상태를 바꾼다. */
        private void setState(UserCoupon userCoupon, String status, Long reservationId, LocalDateTime usedAt) {
            jdbcTemplate.update(
                    "UPDATE p_user_coupons SET status = ?, reservation_id = ?, used_at = ? WHERE user_coupon_id = ?",
                    status,
                    reservationId,
                    usedAt,
                    userCoupon.getId());
        }

        private List<Long> mine(UserCouponStatus status) {
            return ids(userCouponRepository.findMine(USER_ID, status, NOW, ALL));
        }

        @Test
        @DisplayName("상태 필터마다 맞는 쿠폰만 돌려주고, 만료 시각이 지난 AVAILABLE은 EXPIRED 쪽에 든다")
        void filtersByStatus() {
            // 만료 시각이 아직 남은 쿠폰, 만료 시각이 정확히 지금인 쿠폰, 배치가 EXPIRED로 바꾼 쿠폰, 예매에 잡힌 쿠폰, 사용한 쿠폰
            UserCoupon active = issueAt(USER_ID, NOW.minusDays(1));
            UserCoupon expiresNow = issueAt(USER_ID, NOW.minusDays(7));
            UserCoupon storedExpired = issueAt(USER_ID, NOW.minusDays(1));
            setState(storedExpired, "EXPIRED", null, null);
            UserCoupon reserved = issueAt(USER_ID, NOW.minusDays(1));
            setState(reserved, "RESERVED", 7_001L, null);
            UserCoupon used = issueAt(USER_ID, NOW.minusDays(1));
            setState(used, "USED", 7_002L, NOW);
            // 다른 회원의 쿠폰은 어떤 필터에도 나오지 않아야 한다
            UserCoupon others = issueAt(930_002L, NOW.minusDays(1));

            assertThat(mine(UserCouponStatus.AVAILABLE)).containsExactly(active.getId());
            assertThat(mine(UserCouponStatus.EXPIRED))
                    .containsExactlyInAnyOrder(expiresNow.getId(), storedExpired.getId());
            assertThat(mine(UserCouponStatus.RESERVED)).containsExactly(reserved.getId());
            assertThat(mine(UserCouponStatus.USED)).containsExactly(used.getId());
            assertThat(mine(null))
                    .containsExactlyInAnyOrder(
                            active.getId(), expiresNow.getId(), storedExpired.getId(), reserved.getId(), used.getId())
                    .doesNotContain(others.getId());
        }

        @Test
        @DisplayName("만료 시각이 같은 쿠폰도 페이지를 넘길 때 겹치거나 빠지지 않는다")
        void stablePages() {
            long userId = 930_004L;
            // 같은 시각에 받아 만료 시각이 모두 같은 쿠폰 세 개
            List<Long> issued = List.of(
                    issueAt(userId, NOW.minusDays(1)).getId(),
                    issueAt(userId, NOW.minusDays(1)).getId(),
                    issueAt(userId, NOW.minusDays(1)).getId());
            Sort byExpiry = Sort.by(Sort.Direction.ASC, "expiresAt");

            // 두 장씩 두 페이지로 나눠 읽는다
            Page<UserCoupon> first = userCouponRepository.findMine(userId, null, NOW, PageRequest.of(0, 2, byExpiry));
            Page<UserCoupon> second = userCouponRepository.findMine(userId, null, NOW, PageRequest.of(1, 2, byExpiry));

            // 두 페이지를 이으면 ID 오름차순으로 세 장이 한 번씩 나온다
            assertThat(Stream.concat(ids(first).stream(), ids(second).stream()).toList())
                    .isEqualTo(issued);
            assertThat(first.getSort()).isEqualTo(byExpiry);
        }

        @Test
        @DisplayName("페이지 크기만큼 잘라 돌려주고 전체 개수를 함께 준다")
        void pages() {
            long userId = 930_003L;
            for (int i = 0; i < 3; i++) {
                issueAt(userId, NOW.minusDays(1));
            }

            Page<UserCoupon> firstPage = userCouponRepository.findMine(
                    userId, null, NOW, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(firstPage.getTotalElements()).isEqualTo(3);
            assertThat(firstPage.getTotalPages()).isEqualTo(2);
        }
    }
}
