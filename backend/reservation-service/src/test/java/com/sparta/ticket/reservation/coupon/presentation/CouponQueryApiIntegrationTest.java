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
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueService couponIssueService;

    /** 서비스와 같이 초 단위로 자른 현재 시각. */
    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 한 시간 전에 발급을 시작해 7일 뒤 끝나는(ISSUING) 쿠폰. */
    private Coupon issuingCoupon(String name) {
        return couponRepository.save(Coupon.create(name, 5_000, 10, now().minusHours(1), now().plusDays(7), 7, now()));
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
}
