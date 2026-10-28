package com.sparta.ticket.reservation.coupon.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 쿠폰 발급 API(POST /api/coupons/{couponId}/issue)를 Gateway가 넘겨주는 사용자 헤더와 실제 PostgreSQL로 호출한다.
 * 명세는 docs/api.md 쿠폰 절이다. 동시 요청은 별도 동시성 테스트에서 본다.
 */
class CouponIssueApiIntegrationTest extends ReservationIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private CouponRepository couponRepository;

    /** 서비스와 같이 초 단위로 자른 현재 시각. */
    private LocalDateTime now() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 한 시간 전에 발급을 시작해 7일 뒤 끝나는(ISSUING) 쿠폰. */
    private Coupon issuingCoupon(int totalQuantity) {
        return couponRepository.save(Coupon.create(
                "오픈 기념 5,000원 할인", 5_000, totalQuantity, now().minusHours(1), now().plusDays(7), 7, now()));
    }

    /** 사용자 헤더를 붙여 발급을 요청한다. 헤더 값은 Gateway가 인증 뒤 넣어 주는 값이다. */
    private ResultActions issue(Long couponId, long userId, Role role) throws Exception {
        return mockMvc.perform(post("/api/coupons/{couponId}/issue", couponId)
                .header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, role.name()));
    }

    @Test
    @DisplayName("USER가 받으면 201과 회원 쿠폰을 돌려주고, 받은 회원은 헤더의 사용자다")
    void issue() throws Exception {
        Coupon coupon = issuingCoupon(10);

        issue(coupon.getId(), 7L, Role.USER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("쿠폰이 발급되었습니다."))
                .andExpect(jsonPath("$.data.userCouponId").isNumber())
                .andExpect(jsonPath("$.data.couponId").value(coupon.getId()))
                .andExpect(jsonPath("$.data.name").value("오픈 기념 5,000원 할인"))
                .andExpect(jsonPath("$.data.discountAmount").value(5000))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.issuedAt").value(now().format(FORMAT)))
                .andExpect(jsonPath("$.data.expiresAt").value(now().plusDays(7).format(FORMAT)))
                // 발급 응답에는 usedAt이 없다(docs/api.md: UserCoupon에서 usedAt 제외)
                .andExpect(jsonPath("$.data.usedAt").doesNotExist());

        // DB에는 헤더의 회원 ID로 저장되고, 발급 수량을 올린 사람으로 그 회원이 기록된다
        Map<String, Object> userCoupon =
                jdbcTemplate.queryForMap("SELECT user_id FROM p_user_coupons WHERE coupon_id = ?", coupon.getId());
        assertThat(userCoupon.get("user_id")).isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT updated_by FROM p_coupons WHERE coupon_id = ?", String.class, coupon.getId()))
                .isEqualTo("user7@ticket.com");
    }

    @Test
    @DisplayName("USER가 아니면 403 FORBIDDEN이고 발급 수량은 그대로다")
    void notUser() throws Exception {
        Coupon coupon = issuingCoupon(10);

        // @RequireRole은 역할이 정확히 같은지만 보므로 MASTER도 받을 수 없다
        for (Role role : new Role[] {Role.MASTER, Role.SELLER}) {
            issue(coupon.getId(), 1L, role)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT issued_quantity FROM p_coupons WHERE coupon_id = ?", Integer.class, coupon.getId()))
                .isZero();
    }

    @Test
    @DisplayName("사용자 헤더가 없으면 401 UNAUTHORIZED")
    void noUser() throws Exception {
        Coupon coupon = issuingCoupon(10);

        mockMvc.perform(post("/api/coupons/{couponId}/issue", coupon.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("같은 회원이 다시 받으면 409 COUPON_ALREADY_ISSUED")
    void alreadyIssued() throws Exception {
        Coupon coupon = issuingCoupon(10);
        issue(coupon.getId(), 1L, Role.USER).andExpect(status().isCreated());

        issue(coupon.getId(), 1L, Role.USER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_ISSUED"));
    }

    @Test
    @DisplayName("수량이 다 떨어지면 409 COUPON_SOLD_OUT")
    void soldOut() throws Exception {
        Coupon coupon = issuingCoupon(1);
        issue(coupon.getId(), 1L, Role.USER).andExpect(status().isCreated());

        issue(coupon.getId(), 2L, Role.USER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"));
    }

    @Test
    @DisplayName("발급 기간이 아니면 400 COUPON_ISSUE_PERIOD_CLOSED")
    void periodClosed() throws Exception {
        Coupon upcoming =
                couponRepository.save(Coupon.create("쿠폰", 5_000, 10, now().plusDays(1), now().plusDays(7), 7, now()));

        issue(upcoming.getId(), 1L, Role.USER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_ISSUE_PERIOD_CLOSED"));
    }

    @Test
    @DisplayName("없는 쿠폰이면 404 COUPON_NOT_FOUND")
    void notFound() throws Exception {
        issue(-1L, 1L, Role.USER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
    }
}
