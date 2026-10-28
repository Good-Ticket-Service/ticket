package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.InvalidSortException;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.dto.CouponDetailResult;
import com.sparta.ticket.reservation.coupon.application.dto.UserCouponResult;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 쿠폰 조회 서비스의 issuedByMe·쿠폰 이름·표시 상태와 정렬 허용 검사를 실제 PostgreSQL로 확인한다.
 * Note: 다른 테스트의 쿠폰도 같은 테이블에 있어, 목록은 이 테스트가 만든 쿠폰을 찾아 확인한다.
 */
class CouponQueryServiceIntegrationTest extends ReservationIntegrationTest {

    /** 다른 테스트 쿠폰이 섞여도 이 테스트 쿠폰이 한 페이지에 들어오도록 크게 잡는다. */
    private static final Pageable ALL = PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private CouponQueryService couponQueryService;

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private MutableClock clock;

    /** 서비스와 같이 초 단위로 자른 현재 시각. 다른 테스트와 Clock을 함께 쓰므로 상대값으로 쓴다. */
    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 한 시간 전에 발급을 시작해 7일 뒤 끝나는(ISSUING) 쿠폰. 유효 일수는 7일이다. */
    private Coupon issuingCoupon(String name) {
        return couponRepository.save(Coupon.create(name, 5_000, 10, now().minusHours(1), now().plusDays(7), 7, now()));
    }

    /** 목록에서 이 쿠폰의 응답을 찾는다. */
    private static CouponDetailResult find(Page<CouponDetailResult> page, Long couponId) {
        return page.getContent().stream()
                .filter(result -> result.couponId().equals(couponId))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("쿠폰 목록")
    class SearchCoupons {

        @Test
        @DisplayName("받은 회원에게는 issuedByMe가 true, 다른 회원에게는 false이고, 남은 수량이 반영된다")
        void issuedByMe() {
            Coupon coupon = issuingCoupon("목록 쿠폰");
            couponIssueService.issue(coupon.getId(), 950_001L, "user@ticket.com");

            CouponDetailResult mine =
                    find(couponQueryService.searchCoupons(CouponIssueStatus.ISSUING, 950_001L, ALL), coupon.getId());
            CouponDetailResult others =
                    find(couponQueryService.searchCoupons(CouponIssueStatus.ISSUING, 950_002L, ALL), coupon.getId());

            assertThat(mine.issuedByMe()).isTrue();
            assertThat(others.issuedByMe()).isFalse();
            assertThat(mine.issueStatus()).isEqualTo(CouponIssueStatus.ISSUING);
            assertThat(mine.remainingQuantity()).isEqualTo(9);
        }

        @Test
        @DisplayName("고른 발급 상태의 쿠폰만 나온다")
        void filtersByIssueStatus() {
            Coupon issuing = issuingCoupon("발급 중");
            Coupon upcoming = couponRepository.save(
                    Coupon.create("시작 전", 5_000, 10, now().plusDays(1), now().plusDays(7), 7, now()));

            List<Long> ids = couponQueryService.searchCoupons(CouponIssueStatus.UPCOMING, 950_001L, ALL).stream()
                    .map(CouponDetailResult::couponId)
                    .toList();

            assertThat(ids).contains(upcoming.getId()).doesNotContain(issuing.getId());
        }

        @Test
        @DisplayName("허용하지 않은 필드로 정렬하면 InvalidSortException(400 VALIDATION_ERROR)이다")
        void disallowedSort() {
            Pageable byName = PageRequest.of(0, 10, Sort.by("name"));

            assertThatThrownBy(() -> couponQueryService.searchCoupons(CouponIssueStatus.ISSUING, 1L, byName))
                    .isInstanceOf(InvalidSortException.class);
        }
    }

    @Nested
    @DisplayName("쿠폰 상세")
    class GetCoupon {

        @Test
        @DisplayName("쿠폰 정보와 issuedByMe를 돌려준다")
        void getCoupon() {
            Coupon coupon = issuingCoupon("상세 쿠폰");
            couponIssueService.issue(coupon.getId(), 950_011L, "user@ticket.com");

            CouponDetailResult result = couponQueryService.getCoupon(coupon.getId(), 950_011L);

            assertThat(result.name()).isEqualTo("상세 쿠폰");
            assertThat(result.issuedByMe()).isTrue();
            assertThat(couponQueryService.getCoupon(coupon.getId(), 950_012L).issuedByMe())
                    .isFalse();
        }

        @Test
        @DisplayName("없거나 삭제된 쿠폰은 COUPON_NOT_FOUND다")
        void notFound() {
            Coupon deleted = issuingCoupon("삭제 쿠폰");
            couponService.delete(deleted.getId(), "master@ticket.com");

            for (Long couponId : new Long[] {-1L, deleted.getId()}) {
                assertThatThrownBy(() -> couponQueryService.getCoupon(couponId, 1L))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("내 쿠폰")
    class FindMyCoupons {

        @Test
        @DisplayName("쿠폰 이름을 붙여 돌려주고, 만료 시각이 지난 AVAILABLE은 EXPIRED로 보인다")
        void namesAndDisplayStatus() {
            long userId = 950_021L;
            Coupon active = issuingCoupon("쓸 수 있는 쿠폰");
            couponIssueService.issue(active.getId(), userId, "user@ticket.com");
            // 8일 전에 받은 쿠폰은 유효 일수(7일)가 지나 만료 시각이 이미 지났다
            Coupon old = issuingCoupon("지난 쿠폰");
            userCouponRepository.saveIssued(UserCoupon.issue(userId, old, now().minusDays(8)));

            Page<UserCouponResult> all = couponQueryService.findMyCoupons(userId, null, ALL);

            assertThat(all.getContent())
                    .extracting(UserCouponResult::name, UserCouponResult::status)
                    .containsExactlyInAnyOrder(
                            tuple("쓸 수 있는 쿠폰", UserCouponStatus.AVAILABLE), tuple("지난 쿠폰", UserCouponStatus.EXPIRED));
            // 필터와 응답 상태가 같은 경계라, AVAILABLE 필터에는 만료된 쿠폰이 나오지 않는다
            assertThat(couponQueryService
                            .findMyCoupons(userId, UserCouponStatus.AVAILABLE, ALL)
                            .getContent())
                    .extracting(UserCouponResult::name)
                    .containsExactly("쓸 수 있는 쿠폰");
        }

        @Test
        @DisplayName("만료 시각 기준으로 정렬할 수 있고, 허용하지 않은 필드는 InvalidSortException이다")
        void sorts() {
            long userId = 950_031L;
            Coupon sooner = issuingCoupon("먼저 만료");
            Coupon later = issuingCoupon("나중 만료");
            userCouponRepository.saveIssued(UserCoupon.issue(userId, later, now()));
            userCouponRepository.saveIssued(UserCoupon.issue(userId, sooner, now().minusDays(1)));

            Page<UserCouponResult> byExpiry = couponQueryService.findMyCoupons(
                    userId, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "expiresAt")));

            assertThat(byExpiry.getContent()).extracting(UserCouponResult::name).containsExactly("먼저 만료", "나중 만료");
            assertThatThrownBy(() -> couponQueryService.findMyCoupons(
                            userId, null, PageRequest.of(0, 10, Sort.by("issueEndAt"))))
                    .isInstanceOf(InvalidSortException.class);
        }
    }
}
