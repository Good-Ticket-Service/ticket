package com.sparta.ticket.reservation.coupon.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.support.PostgresIntegrationTest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 쿠폰 관리자 API를 Gateway가 넘겨주는 사용자 헤더와 실제 PostgreSQL로 호출한다. 명세는 docs/coupon/api.md다.
 *
 * <p>Note: @AutoConfigureMockMvc를 붙이면 설정이 달라져 Spring이 컨텍스트를 하나 더 띄운다. 새 컨텍스트는 모든 테스트 클래스가
 * 공유하는 PostgreSQL 컨테이너에 schema.sql을 다시 실행해 "p_coupons already exists"로 실패한다. 그래서 다른 통합 테스트와
 * 같은 컨텍스트를 쓰고 MockMvc는 직접 만든다.
 */
class CouponApiIntegrationTest extends PostgresIntegrationTest {

    private static final String MASTER_EMAIL = "master@ticket.com";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Role role) {
        return request.header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, MASTER_EMAIL)
                .header(UserHeaders.ROLE, role.name());
    }

    private static String format(LocalDateTime time) {
        return time.format(FORMAT);
    }

    private static String createBody(LocalDateTime start, LocalDateTime end) {
        return """
                {"name": "오픈 기념 5,000원 할인", "discountAmount": 5000, "totalQuantity": 1000,
                 "issueStartAt": "%s", "issueEndAt": "%s", "validDays": 7}
                """.formatted(format(start), format(end));
    }

    @Nested
    @DisplayName("POST /api/coupons 쿠폰 생성")
    class Create {

        @Test
        @DisplayName("MASTER가 만들면 201과 쿠폰을 돌려주고, 생성자는 요청한 관리자다")
        void create() throws Exception {
            LocalDateTime start = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);
            LocalDateTime end = start.plusDays(6);

            String body = mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, end)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("쿠폰이 생성되었습니다."))
                    .andExpect(jsonPath("$.data.couponId").isNumber())
                    .andExpect(jsonPath("$.data.name").value("오픈 기념 5,000원 할인"))
                    .andExpect(jsonPath("$.data.discountAmount").value(5000))
                    .andExpect(jsonPath("$.data.totalQuantity").value(1000))
                    .andExpect(jsonPath("$.data.remainingQuantity").value(1000))
                    .andExpect(jsonPath("$.data.issueStartAt").value(format(start)))
                    .andExpect(jsonPath("$.data.issueEndAt").value(format(end)))
                    .andExpect(jsonPath("$.data.validDays").value(7))
                    .andExpect(jsonPath("$.data.issueStatus").value("UPCOMING"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            Number couponId = JsonPath.read(body, "$.data.couponId");
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT issued_quantity, created_by FROM p_coupons WHERE coupon_id = ?", couponId.longValue());
            assertThat(row.get("issued_quantity")).isEqualTo(0);
            assertThat(row.get("created_by")).isEqualTo(MASTER_EMAIL);
        }

        @Test
        @DisplayName("발급 시작 시각이 지났으면 ISSUING으로 만들어진다")
        void createIssuing() throws Exception {
            LocalDateTime start = LocalDateTime.now().minusHours(1);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, start.plusDays(7))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.issueStatus").value("ISSUING"));
        }

        @Test
        @DisplayName("MASTER가 아니면 403 FORBIDDEN")
        void notMaster() throws Exception {
            LocalDateTime start = LocalDateTime.now().plusDays(1);

            for (Role role : new Role[] {Role.USER, Role.SELLER}) {
                mockMvc.perform(as(post("/api/coupons"), role)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(start, start.plusDays(6))))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }

        @Test
        @DisplayName("필수 값이 없으면 400 VALIDATION_ERROR와 필드별 사유")
        void missingFields() throws Exception {
            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.path").value("/api/coupons"))
                    .andExpect(jsonPath("$.errors").isArray());
        }

        @Test
        @DisplayName("발급 종료 시각이 시작 시각보다 늦지 않으면 400 VALIDATION_ERROR")
        void invalidPeriod() throws Exception {
            LocalDateTime start = LocalDateTime.now().plusDays(1);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, start.minusHours(1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }
}
