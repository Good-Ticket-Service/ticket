package com.sparta.ticket.reservation.coupon.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.application.CouponIssueService;
import com.sparta.ticket.reservation.coupon.application.CouponService;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 쿠폰 조회 API를 Gateway가 넘겨주는 사용자 헤더와 실제 PostgreSQL로 호출한다. 명세는 docs/api.md 쿠폰 절이다.
 * Note: 다른 테스트의 쿠폰도 같은 테이블에 있다. 목록은 기본 정렬(createdAt,DESC)로 방금 만든 쿠폰이 첫 페이지에 오게 하고,
 * 그 쿠폰이 들어 있는지·빠졌는지로 확인한다.
 */
class CouponQueryApiIntegrationTest extends ReservationIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private UserCouponRepository userCouponRepository;

    /** 서비스와 같이 초 단위로 자른 현재 시각. */
    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 하루 전에 만들어 한 시간 전에 발급을 시작한, 7일 뒤 끝나는(ISSUING) 쿠폰. */
    private Coupon issuingCoupon(String name) {
        return couponRepository.save(
                Coupon.create(name, 5_000, 10, now().minusHours(1), now().plusDays(7), 7, now().minusDays(1)));
    }

    /** Gateway가 인증 뒤 넣어 주는 사용자 헤더를 붙여 요청한다. */
    private ResultActions perform(MockHttpServletRequestBuilder request, long userId, Role role) throws Exception {
        return mockMvc.perform(request.header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, role.name()));
    }

    @Nested
    @DisplayName("GET /api/coupons")
    class SearchCoupons {

        @Test
        @DisplayName("200과 페이지를 돌려주고, 정렬을 보내지 않으면 응답 sort는 기본값 createdAt,DESC다")
        void page() throws Exception {
            Coupon coupon = issuingCoupon("목록 API 쿠폰");
            couponIssueService.issue(coupon.getId(), 960_001L, "user@ticket.com");
            String issuedByMe = "$.data.content[?(@.couponId == " + coupon.getId() + ")].issuedByMe";

            perform(get("/api/coupons"), 960_001L, Role.USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(10))
                    .andExpect(jsonPath("$.data.totalElements").isNumber())
                    .andExpect(jsonPath("$.data.totalPages").isNumber())
                    .andExpect(jsonPath("$.data.sort").value("createdAt,DESC"))
                    // 방금 만든 쿠폰은 가장 최근이라 첫 항목이고, 받은 회원에게는 issuedByMe가 true다
                    .andExpect(jsonPath("$.data.content[0].couponId").value(coupon.getId()))
                    .andExpect(jsonPath("$.data.content[0].name").value("목록 API 쿠폰"))
                    .andExpect(jsonPath("$.data.content[0].remainingQuantity").value(9))
                    .andExpect(jsonPath("$.data.content[0].issueStatus").value("ISSUING"))
                    .andExpect(jsonPath(issuedByMe, contains(true)));

            // 받지 않은 회원, 받을 수 없는 SELLER도 볼 수 있고 issuedByMe는 false다
            perform(get("/api/coupons"), 960_002L, Role.USER).andExpect(jsonPath(issuedByMe, contains(false)));
            perform(get("/api/coupons"), 960_003L, Role.SELLER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(issuedByMe, contains(false)));
        }

        @Test
        @DisplayName("issueStatus를 보내지 않으면 ISSUING만, 보내면 그 상태의 쿠폰만 나온다")
        void filtersByIssueStatus() throws Exception {
            Coupon upcoming = couponRepository.save(
                    Coupon.create("시작 전 쿠폰", 5_000, 10, now().plusDays(1), now().plusDays(7), 7, now()));
            String ids = "$.data.content[*].couponId";

            perform(get("/api/coupons"), 1L, Role.USER)
                    .andExpect(jsonPath(ids, not(hasItem(upcoming.getId().intValue()))));
            perform(get("/api/coupons").param("issueStatus", "UPCOMING"), 1L, Role.USER)
                    .andExpect(jsonPath(ids, hasItem(upcoming.getId().intValue())));
        }

        @Test
        @DisplayName("보낸 정렬을 같은 형식으로 돌려주고, 허용하지 않은 size는 10으로 보정한다")
        void sortAndSize() throws Exception {
            perform(get("/api/coupons").param("sort", "issueEndAt,ASC").param("size", "7"), 1L, Role.USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sort").value("issueEndAt,ASC"))
                    .andExpect(jsonPath("$.data.size").value(10));
        }

        @Test
        @DisplayName("허용하지 않은 정렬 필드나 없는 issueStatus면 400 VALIDATION_ERROR")
        void badRequest() throws Exception {
            perform(get("/api/coupons").param("sort", "name,ASC"), 1L, Role.USER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            perform(get("/api/coupons").param("issueStatus", "OPEN"), 1L, Role.USER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("사용자 헤더가 없으면 401 UNAUTHORIZED")
        void noUser() throws Exception {
            mockMvc.perform(get("/api/coupons"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("GET /api/coupons/{couponId}")
    class GetCoupon {

        @Test
        @DisplayName("200과 쿠폰 정보를 돌려주고, 받은 회원에게만 issuedByMe가 true다")
        void detail() throws Exception {
            Coupon coupon = issuingCoupon("상세 API 쿠폰");
            couponIssueService.issue(coupon.getId(), 961_001L, "user@ticket.com");

            perform(get("/api/coupons/{couponId}", coupon.getId()), 961_001L, Role.USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.couponId").value(coupon.getId()))
                    .andExpect(jsonPath("$.data.name").value("상세 API 쿠폰"))
                    .andExpect(jsonPath("$.data.discountAmount").value(5000))
                    .andExpect(jsonPath("$.data.totalQuantity").value(10))
                    .andExpect(jsonPath("$.data.remainingQuantity").value(9))
                    .andExpect(jsonPath("$.data.issueStartAt")
                            .value(now().minusHours(1).format(FORMAT)))
                    .andExpect(jsonPath("$.data.issueEndAt")
                            .value(now().plusDays(7).format(FORMAT)))
                    .andExpect(jsonPath("$.data.validDays").value(7))
                    .andExpect(jsonPath("$.data.issueStatus").value("ISSUING"))
                    .andExpect(jsonPath("$.data.issuedByMe").value(true));

            // 받지 않은 회원과 SELLER는 issuedByMe가 false다
            perform(get("/api/coupons/{couponId}", coupon.getId()), 961_002L, Role.USER)
                    .andExpect(jsonPath("$.data.issuedByMe").value(false));
            perform(get("/api/coupons/{couponId}", coupon.getId()), 961_003L, Role.SELLER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issuedByMe").value(false));
        }

        @Test
        @DisplayName("없거나 삭제된 쿠폰은 404 COUPON_NOT_FOUND")
        void notFound() throws Exception {
            Coupon deleted = issuingCoupon("삭제된 쿠폰");
            couponService.delete(deleted.getId(), "master@ticket.com");

            for (Long couponId : new Long[] {-1L, deleted.getId()}) {
                perform(get("/api/coupons/{couponId}", couponId), 1L, Role.USER)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
            }
        }

        @Test
        @DisplayName("사용자 헤더가 없으면 401 UNAUTHORIZED")
        void noUser() throws Exception {
            Coupon coupon = issuingCoupon("헤더 없는 요청");

            mockMvc.perform(get("/api/coupons/{couponId}", coupon.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("GET /api/coupons/me")
    class FindMyCoupons {

        @Test
        @DisplayName("200과 헤더 사용자의 쿠폰만 돌려주고, 만료 시각이 지난 AVAILABLE은 EXPIRED로 보인다")
        void mine() throws Exception {
            long userId = 962_001L;
            Coupon active = issuingCoupon("쓸 수 있는 쿠폰");
            couponIssueService.issue(active.getId(), userId, "user@ticket.com");
            // 8일 전에 받은 쿠폰은 유효 일수(7일)가 지나 만료 시각이 이미 지났다
            Coupon old = issuingCoupon("지난 쿠폰");
            userCouponRepository.saveIssued(UserCoupon.issue(userId, old, now().minusDays(8)));
            // 다른 회원의 쿠폰은 나오지 않아야 한다
            couponIssueService.issue(active.getId(), 962_002L, "other@ticket.com");

            perform(get("/api/coupons/me").param("sort", "expiresAt,DESC"), userId, Role.USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.sort").value("expiresAt,DESC"))
                    // 만료 시각이 늦은 쿠폰이 먼저 온다
                    .andExpect(jsonPath("$.data.content[0].couponId").value(active.getId()))
                    .andExpect(jsonPath("$.data.content[0].userCouponId").isNumber())
                    .andExpect(jsonPath("$.data.content[0].name").value("쓸 수 있는 쿠폰"))
                    .andExpect(jsonPath("$.data.content[0].discountAmount").value(5000))
                    .andExpect(jsonPath("$.data.content[0].status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.data.content[0].issuedAt").value(now().format(FORMAT)))
                    .andExpect(jsonPath("$.data.content[0].expiresAt")
                            .value(now().plusDays(7).format(FORMAT)))
                    // 발급 응답과 달리 내 쿠폰에는 usedAt이 있고, 아직 쓰지 않았으면 null이다
                    .andExpect(jsonPath("$.data.content[0].usedAt").isEmpty())
                    .andExpect(jsonPath("$.data.content[1].couponId").value(old.getId()))
                    .andExpect(jsonPath("$.data.content[1].status").value("EXPIRED"));
        }

        @Test
        @DisplayName("status를 보내면 그 상태의 쿠폰만 나온다")
        void filtersByStatus() throws Exception {
            long userId = 962_011L;
            couponIssueService.issue(issuingCoupon("쓸 수 있는 쿠폰").getId(), userId, "user@ticket.com");
            userCouponRepository.saveIssued(UserCoupon.issue(userId, issuingCoupon("지난 쿠폰"), now().minusDays(8)));

            perform(get("/api/coupons/me").param("status", "EXPIRED"), userId, Role.USER)
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].name").value("지난 쿠폰"));
        }

        @Test
        @DisplayName("받은 쿠폰이 없으면 빈 목록이다(SELLER 포함)")
        void empty() throws Exception {
            perform(get("/api/coupons/me"), 962_021L, Role.SELLER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.sort").value("createdAt,DESC"));
        }

        @Test
        @DisplayName("허용하지 않은 정렬 필드나 없는 status면 400 VALIDATION_ERROR")
        void badRequest() throws Exception {
            // issueEndAt은 쿠폰 목록에서만 쓰는 정렬이다
            perform(get("/api/coupons/me").param("sort", "issueEndAt,ASC"), 1L, Role.USER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            perform(get("/api/coupons/me").param("status", "DELETED"), 1L, Role.USER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("사용자 헤더가 없으면 401 UNAUTHORIZED")
        void noUser() throws Exception {
            mockMvc.perform(get("/api/coupons/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }
}
