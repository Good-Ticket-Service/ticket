package com.sparta.ticket.reservation.coupon.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 쿠폰 관리자 API를 Gateway가 넘겨주는 사용자 헤더와 실제 PostgreSQL로 호출한다. 명세는 docs/api.md 쿠폰 절이다.
 * reservation-service 통합 테스트의 공통 부모(ReservationIntegrationTest)를 상속해 다른 통합 테스트와 같은 컨텍스트를 쓴다.
 */
class CouponApiIntegrationTest extends ReservationIntegrationTest {

    private static final String MASTER_EMAIL = "master@ticket.com";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

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

    /** 정률 쿠폰 생성 본문. discount에는 discountType을 뺀 할인 값들을 JSON 조각으로 넘긴다. */
    private static String rateBody(String discount, LocalDateTime start) {
        return """
                {"name": "정률 쿠폰", "discountType": "RATE", %s, "totalQuantity": 100,
                 "issueStartAt": "%s", "issueEndAt": "%s", "validDays": 7}
                """.formatted(discount, format(start), format(start.plusDays(6)));
    }

    /** 구분을 정한 쿠폰 생성 본문. validDays가 null이면 보내지 않는다. */
    private static String categoryBody(String category, Integer validDays, LocalDateTime start) {
        String days = validDays == null ? "" : ", \"validDays\": " + validDays;
        return """
                {"name": "웰컴 쿠폰", "category": "%s", "discountAmount": 3000, "totalQuantity": 100,
                 "issueStartAt": "%s", "issueEndAt": "%s"%s}
                """.formatted(category, format(start), format(start.plusDays(6)), days);
    }

    @Nested
    @DisplayName("POST /api/coupons 쿠폰 생성")
    class Create {

        @Test
        @DisplayName("MASTER가 만들면 201과 쿠폰을 돌려주고, 생성자는 요청한 관리자다")
        void create() throws Exception {
            LocalDateTime start = clock.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);
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
        @DisplayName("발급 시작 시각이 지났으면 현재 시각으로 저장되고 ISSUING으로 만들어진다")
        void createIssuing() throws Exception {
            LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
            LocalDateTime start = now.minusHours(1);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, start.plusDays(7))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.issueStartAt").value(format(now)))
                    .andExpect(jsonPath("$.data.issueStatus").value("ISSUING"));
        }

        @Test
        @DisplayName("MASTER가 아니면 403 FORBIDDEN")
        void notMaster() throws Exception {
            LocalDateTime start = clock.now().plusDays(1);

            for (Role role : new Role[] {Role.USER, Role.SELLER}) {
                mockMvc.perform(as(post("/api/coupons"), role)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(start, start.plusDays(6))))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }

        @Test
        @DisplayName("할인 종류를 보내지 않으면 정액이고, 응답에 할인 정책 전체가 담긴다(#348)")
        void createDefaultsToFixed() throws Exception {
            LocalDateTime start = clock.now().plusDays(1);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, start.plusDays(6))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.discountType").value("FIXED"))
                    .andExpect(jsonPath("$.data.discountAmount").value(5000))
                    .andExpect(jsonPath("$.data.discountRate").doesNotExist())
                    .andExpect(jsonPath("$.data.maxDiscountAmount").doesNotExist())
                    .andExpect(jsonPath("$.data.minBaseAmount").doesNotExist());
        }

        @Test
        @DisplayName("정률 쿠폰은 할인율·최대 할인액·최소 적용 금액으로 만들고 할인액은 없다(#348)")
        void createRate() throws Exception {
            LocalDateTime start = clock.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(rateBody(
                                    "\"discountRate\": 10, \"maxDiscountAmount\": 5000, \"minBaseAmount\": 30000",
                                    start)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.discountType").value("RATE"))
                    .andExpect(jsonPath("$.data.discountAmount").doesNotExist())
                    .andExpect(jsonPath("$.data.discountRate").value(10))
                    .andExpect(jsonPath("$.data.maxDiscountAmount").value(5000))
                    .andExpect(jsonPath("$.data.minBaseAmount").value(30000));
        }

        @Test
        @DisplayName("종류에 맞지 않는 할인 값이나 범위를 벗어난 할인율은 400 VALIDATION_ERROR(#348)")
        void createInvalidDiscountPolicy() throws Exception {
            LocalDateTime start = clock.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);

            for (String body : new String[] {
                rateBody("\"discountAmount\": 1000, \"discountRate\": 10", start),
                rateBody("\"discountRate\": 100", start),
                rateBody("\"maxDiscountAmount\": 5000", start),
                createBody(start, start.plusDays(6))
                        .replace("\"discountAmount\": 5000", "\"discountAmount\": 5000, \"maxDiscountAmount\": 500"),
                createBody(start, start.plusDays(6))
                        .replace("\"discountAmount\": 5000", "\"discountAmount\": 5000, \"minBaseAmount\": 0")
            }) {
                mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }

        @Test
        @DisplayName("구분을 보내지 않으면 일반 쿠폰이다(#357)")
        void createDefaultsToGeneral() throws Exception {
            LocalDateTime start = clock.now().plusDays(1);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, start.plusDays(6))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.category").value("GENERAL"));
        }

        @Test
        @DisplayName("웰컴 쿠폰은 유효 일수를 보내지 않으면 7일로 만들어진다(#357)")
        void createWelcome() throws Exception {
            LocalDateTime start = clock.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(categoryBody("WELCOME", null, start)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.category").value("WELCOME"))
                    .andExpect(jsonPath("$.data.validDays").value(7));
        }

        @Test
        @DisplayName("웰컴 쿠폰을 7일이 아닌 유효 일수로 만들거나, 일반 쿠폰을 유효 일수 없이 만들면 400 VALIDATION_ERROR(#357)")
        void createInvalidCategory() throws Exception {
            LocalDateTime start = clock.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);

            for (String body :
                    new String[] {categoryBody("WELCOME", 30, start), categoryBody("GENERAL", null, start)}) {
                mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
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
            LocalDateTime start = clock.now().plusDays(1);

            mockMvc.perform(as(post("/api/coupons"), Role.MASTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(start, start.minusHours(1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("PATCH /api/coupons/{couponId} 쿠폰 수정")
    class Update {

        private ResultActions patch(Long couponId, String body) throws Exception {
            return mockMvc.perform(as(MockMvcRequestBuilders.patch("/api/coupons/{couponId}", couponId), Role.MASTER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        @Test
        @DisplayName("발급 시작 전에는 보낸 항목만 바뀌고 나머지는 그대로다")
        void beforeStart() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            patch(coupon.getId(), """
                            {"discountType": "FIXED", "discountAmount": 3000, "validDays": 14}
                            """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.couponId").value(coupon.getId()))
                    .andExpect(jsonPath("$.data.name").value("오픈 기념 5,000원 할인"))
                    .andExpect(jsonPath("$.data.discountType").value("FIXED"))
                    .andExpect(jsonPath("$.data.discountAmount").value(3000))
                    .andExpect(jsonPath("$.data.validDays").value(14))
                    .andExpect(jsonPath("$.data.issueStatus").value("UPCOMING"));
        }

        @Test
        @DisplayName("발급 시작 후 이름·종료 시각·총 수량을 바꿔도 발급 수량은 그대로다")
        void afterStartKeepsIssuedQuantity() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));
            issueToUsers(coupon, 300);
            LocalDateTime newEnd = clock.now().plusDays(14).truncatedTo(ChronoUnit.SECONDS);

            patch(coupon.getId(), """
                            {"name": "연장", "totalQuantity": 1500, "issueEndAt": "%s"}
                            """.formatted(format(newEnd)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("연장"))
                    .andExpect(jsonPath("$.data.totalQuantity").value(1500))
                    .andExpect(jsonPath("$.data.remainingQuantity").value(1200))
                    .andExpect(jsonPath("$.data.issueEndAt").value(format(newEnd)))
                    .andExpect(jsonPath("$.data.issueStatus").value("ISSUING"));

            assertThat(userCouponCount(coupon.getId())).isEqualTo(300);
        }

        @Test
        @DisplayName("할인 정책은 묶음 전체를 바꾼다. 정액을 정률로 바꾸면 할인액은 없어지고, 함께 보내지 않은 값도 없어진다(#348)")
        void replaceDiscountPolicy() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            patch(coupon.getId(), """
                            {"discountType": "RATE", "discountRate": 10, "maxDiscountAmount": 5000, "minBaseAmount": 30000}
                            """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.discountType").value("RATE"))
                    .andExpect(jsonPath("$.data.discountAmount").doesNotExist())
                    .andExpect(jsonPath("$.data.discountRate").value(10))
                    .andExpect(jsonPath("$.data.maxDiscountAmount").value(5000))
                    .andExpect(jsonPath("$.data.minBaseAmount").value(30000));

            patch(coupon.getId(), """
                            {"discountType": "RATE", "discountRate": 20}
                            """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.discountRate").value(20))
                    .andExpect(jsonPath("$.data.maxDiscountAmount").doesNotExist())
                    .andExpect(jsonPath("$.data.minBaseAmount").doesNotExist());
        }

        @Test
        @DisplayName("discountType 없이 할인액만 보내면 정액으로 바꾼다(기존 화면 요청과 같다)(#348)")
        void discountTypeDefaultsToFixed() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            patch(coupon.getId(), """
                            {"discountAmount": 3000}
                            """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.discountType").value("FIXED"))
                    .andExpect(jsonPath("$.data.discountAmount").value(3000));
        }

        @Test
        @DisplayName("종류에 맞지 않는 할인 값을 보내면 400 VALIDATION_ERROR이고 그대로다. 종류 없이 정률 값만 보내도 정액에 맞지 않아 400이다(#348)")
        void discountPolicyMismatch() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            for (String body : new String[] {
                "{\"discountRate\": 10}",
                "{\"discountType\": \"FIXED\", \"discountRate\": 10}",
                "{\"discountType\": \"RATE\", \"discountRate\": 100}"
            }) {
                patch(coupon.getId(), body)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
            Integer discountAmount = jdbcTemplate.queryForObject(
                    "SELECT discount_amount FROM p_coupons WHERE coupon_id = ?", Integer.class, coupon.getId());
            assertThat(discountAmount).isEqualTo(5000);
        }

        @Test
        @DisplayName("시작 전 일반 쿠폰을 웰컴으로 바꾸려면 validDays 7을 함께 보내야 한다(#357)")
        void changeToWelcome() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            patch(coupon.getId(), """
                            {"category": "WELCOME", "validDays": 30}
                            """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            patch(coupon.getId(), """
                            {"category": "WELCOME", "validDays": 7}
                            """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.category").value("WELCOME"))
                    .andExpect(jsonPath("$.data.validDays").value(7));
        }

        @Test
        @DisplayName("발급 시작 후 구분을 바꾸면 409 COUPON_NOT_MODIFIABLE(#357)")
        void categoryNotModifiable() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));

            patch(coupon.getId(), """
                            {"category": "WELCOME", "validDays": 7}
                            """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_MODIFIABLE"));
        }

        @Test
        @DisplayName("발급 시작 후 할인 정책을 바꾸면 409 COUPON_NOT_MODIFIABLE")
        void notModifiable() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));

            patch(coupon.getId(), """
                            {"discountType": "RATE", "discountRate": 10}
                            """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_MODIFIABLE"));
        }

        @Test
        @DisplayName("총 수량을 받은 회원 수보다 적게 바꾸면 409 COUPON_QUANTITY_BELOW_ISSUED")
        void quantityBelowIssued() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));
            issueToUsers(coupon, 300);

            patch(coupon.getId(), """
                            {"totalQuantity": 299}
                            """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_QUANTITY_BELOW_ISSUED"));
        }

        @Test
        @DisplayName("발급을 중단한 쿠폰의 종료 시각을 미래로 바꾸면 409 COUPON_ALREADY_CLOSED이고 ENDED 그대로다")
        void closedCannotReopen() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));
            mockMvc.perform(as(post("/api/coupons/{couponId}/close", coupon.getId()), Role.MASTER))
                    .andExpect(status().isOk());
            LocalDateTime future = clock.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS);

            patch(coupon.getId(), """
                            {"issueEndAt": "%s"}
                            """.formatted(format(future)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_ALREADY_CLOSED"));

            LocalDateTime end = jdbcTemplate.queryForObject(
                    "SELECT issue_end_at FROM p_coupons WHERE coupon_id = ?", LocalDateTime.class, coupon.getId());
            assertThat(end).isBeforeOrEqualTo(clock.now());
        }

        @Test
        @DisplayName("없거나 삭제된 쿠폰이면 404 COUPON_NOT_FOUND")
        void notFound() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));
            jdbcTemplate.update(
                    "UPDATE p_coupons SET deleted_at = now(), deleted_by = 'master' WHERE coupon_id = ?",
                    coupon.getId());

            patch(coupon.getId(), "{\"name\": \"새 이름\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
            patch(Long.MAX_VALUE, "{\"name\": \"새 이름\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
        }

        @Test
        @DisplayName("바꿀 항목이 하나도 없으면(빈 본문, 모두 null, 모르는 필드만) 400 VALIDATION_ERROR이고 쿠폰은 그대로다")
        void noFields() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            patch(coupon.getId(), "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            patch(coupon.getId(), "{\"name\": null, \"totalQuantity\": null}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            // 필드 이름을 잘못 보내면 모르는 필드는 무시돼 빈 요청이 된다
            patch(coupon.getId(), "{\"title\": \"새 이름\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT name FROM p_coupons WHERE coupon_id = ?", String.class, coupon.getId()))
                    .isEqualTo("오픈 기념 5,000원 할인");
        }

        @Test
        @DisplayName("발급이 끝난 쿠폰의 총 수량을 바꾸면 409 COUPON_ALREADY_CLOSED이고, 이름은 바꿀 수 있다")
        void closedQuantity() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));
            mockMvc.perform(as(post("/api/coupons/{couponId}/close", coupon.getId()), Role.MASTER))
                    .andExpect(status().isOk());

            patch(coupon.getId(), "{\"totalQuantity\": 2000}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_ALREADY_CLOSED"));
            patch(coupon.getId(), "{\"name\": \"종료된 이벤트\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("종료된 이벤트"))
                    .andExpect(jsonPath("$.data.totalQuantity").value(1000))
                    .andExpect(jsonPath("$.data.issueStatus").value("ENDED"));
        }

        @Test
        @DisplayName("시작 전 쿠폰의 시작 시각을 과거로 바꾸면 현재 시각으로 저장되고 ISSUING이 된다")
        void pastStart() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));
            LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);

            patch(coupon.getId(), """
                            {"issueStartAt": "%s"}
                            """.formatted(format(now.minusDays(3))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issueStartAt").value(format(now)))
                    .andExpect(jsonPath("$.data.issueStatus").value("ISSUING"));
        }

        @Test
        @DisplayName("공백뿐인 이름이나 1보다 작은 수량은 400 VALIDATION_ERROR")
        void invalidValues() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            patch(coupon.getId(), "{\"name\": \"   \"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            patch(coupon.getId(), "{\"totalQuantity\": 0}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("MASTER가 아니면 403 FORBIDDEN")
        void notMaster() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            mockMvc.perform(as(MockMvcRequestBuilders.patch("/api/coupons/{couponId}", coupon.getId()), Role.USER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"새 이름\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("POST /api/coupons/{couponId}/close 쿠폰 발급 중단")
    class Close {

        private ResultActions close(Long couponId, Role role) throws Exception {
            return mockMvc.perform(as(post("/api/coupons/{couponId}/close", couponId), role));
        }

        @Test
        @DisplayName("발급 중인 쿠폰은 종료 시각이 현재로 당겨져 ENDED가 되고, 시작 시각은 그대로다")
        void closeIssuing() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));

            close(coupon.getId(), Role.MASTER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issueStatus").value("ENDED"))
                    .andExpect(jsonPath("$.data.issueStartAt").value(format(coupon.getIssueStartAt())));

            Map<String, Object> row = periodRow(coupon.getId());
            LocalDateTime end = ((Timestamp) row.get("issue_end_at")).toLocalDateTime();
            assertThat(end).isBefore(coupon.getIssueEndAt()).isBeforeOrEqualTo(clock.now());
        }

        @Test
        @DisplayName("시작 전 쿠폰은 시작·종료 시각이 같아지고 ENDED가 된다 (ck_p_coupons_period 통과)")
        void closeUpcoming() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            close(coupon.getId(), Role.MASTER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issueStatus").value("ENDED"));

            Map<String, Object> row = periodRow(coupon.getId());
            assertThat(row.get("issue_start_at")).isEqualTo(row.get("issue_end_at"));
        }

        @Test
        @DisplayName("이미 끝난 쿠폰을 다시 중단하면 409 COUPON_ALREADY_CLOSED")
        void alreadyClosed() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));
            close(coupon.getId(), Role.MASTER).andExpect(status().isOk());

            close(coupon.getId(), Role.MASTER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_ALREADY_CLOSED"));
        }

        @Test
        @DisplayName("없는 쿠폰이면 404 COUPON_NOT_FOUND")
        void notFound() throws Exception {
            close(Long.MAX_VALUE, Role.MASTER)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
        }

        @Test
        @DisplayName("MASTER가 아니면 403 FORBIDDEN")
        void notMaster() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));

            close(coupon.getId(), Role.USER)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        private Map<String, Object> periodRow(Long couponId) {
            return jdbcTemplate.queryForMap(
                    "SELECT issue_start_at, issue_end_at FROM p_coupons WHERE coupon_id = ?", couponId);
        }
    }

    @Nested
    @DisplayName("DELETE /api/coupons/{couponId} 쿠폰 삭제")
    class Delete {

        private ResultActions delete(Long couponId, Role role) throws Exception {
            return mockMvc.perform(as(MockMvcRequestBuilders.delete("/api/coupons/{couponId}", couponId), role));
        }

        @Test
        @DisplayName("아무도 받지 않은 발급 중 쿠폰은 204로 논리 삭제되고, 삭제자와 마지막 수정자는 요청한 관리자다")
        void deleteNotIssued() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));

            delete(coupon.getId(), Role.MASTER)
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT deleted_at, deleted_by, updated_by FROM p_coupons WHERE coupon_id = ?", coupon.getId());
            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(row.get("deleted_by")).isEqualTo(MASTER_EMAIL);
            // 저장소로 만든 쿠폰은 요청 밖이라 SYSTEM이 수정자였다. 삭제도 행을 바꾼 것이라 마지막 수정자가 관리자로 바뀐다
            assertThat(row.get("updated_by")).isEqualTo(MASTER_EMAIL);
            assertThat(couponRepository.findById(coupon.getId())).isEmpty();
        }

        @Test
        @DisplayName("한 장이라도 발급된 쿠폰은 409 COUPON_NOT_DELETABLE이고 삭제되지 않는다")
        void issuedCannotBeDeleted() throws Exception {
            Coupon coupon = saveCoupon(clock.now().minusHours(1));
            issueToUsers(coupon, 1);

            delete(coupon.getId(), Role.MASTER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_DELETABLE"));

            assertThat(couponRepository.findById(coupon.getId())).isPresent();
        }

        @Test
        @DisplayName("없거나 이미 삭제된 쿠폰이면 404 COUPON_NOT_FOUND")
        void notFound() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));
            delete(coupon.getId(), Role.MASTER).andExpect(status().isNoContent());

            delete(coupon.getId(), Role.MASTER)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
            delete(Long.MAX_VALUE, Role.MASTER)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
        }

        @Test
        @DisplayName("MASTER가 아니면 403 FORBIDDEN이고 삭제되지 않는다")
        void notMaster() throws Exception {
            Coupon coupon = saveCoupon(clock.now().plusDays(1));

            delete(coupon.getId(), Role.USER)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            assertThat(couponRepository.findById(coupon.getId())).isPresent();
        }
    }

    /** 발급 시작 시각을 정해 쿠폰을 저장한다. 종료 시각은 7일 뒤다. */
    /** 발급 시작 하루 전에 만든 쿠폰. 과거 시작 시각은 현재로 맞춰 저장되므로, 시작 시각을 보낸 값 그대로 두려면 시작 전에 만든다. */
    private Coupon saveCoupon(LocalDateTime start) {
        LocalDateTime issueStartAt = start.truncatedTo(ChronoUnit.SECONDS);
        return couponRepository.save(Coupon.create(
                "오픈 기념 5,000원 할인", 5_000, 1_000, issueStartAt, issueStartAt.plusDays(7), 7, issueStartAt.minusDays(1)));
    }

    /**
     * 발급 기능 대신 회원 쿠폰을 직접 저장한다. Redis 발급 판단 정보는 없는 상태라, 관리자 기능은 DB에서 채운 뒤 판단한다(#300).
     * 발급 수량의 원본은 회원 쿠폰 수다(p_coupons.issued_quantity는 주기적으로 갱신하는 참고값).
     */
    private void issueToUsers(Coupon coupon, int count) {
        for (long userId = 1; userId <= count; userId++) {
            userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, clock.now()));
        }
    }

    private int userCouponCount(Long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_user_coupons WHERE coupon_id = ?", Integer.class, couponId);
    }
}
