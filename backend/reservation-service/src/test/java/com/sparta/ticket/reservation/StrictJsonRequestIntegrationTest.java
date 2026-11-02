package com.sparta.ticket.reservation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 요청 본문의 숫자·boolean을 JSON 값 그대로만 받는지 모듈별 대표 요청으로 확인한다(이슈 #233, JacksonConfig).
 * 정수 필드의 소수(5.9)와 문자열 숫자("6")·문자열 boolean("true")은 다른 값으로 바뀌어 처리되지 않고 400 VALIDATION_ERROR다.
 * 대기열 API는 요청 본문이 없어 대상이 아니다.
 */
class StrictJsonRequestIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
    }

    private ResultActions postJson(String path, String role, String body, Object... uriVars) throws Exception {
        return mockMvc.perform(post(path, uriVars)
                .header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "user1")
                .header(UserHeaders.ROLE, role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static void badRequest(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("좌석 선점: scheduleSeatId에 소수나 문자열 숫자가 오면 400이고, 정수면 본문을 통과해 좌석 확인까지 간다")
    void seatHold() throws Exception {
        String path = "/api/seats/schedules/{scheduleId}/holds";
        badRequest(postJson(path, "USER", "{\"scheduleSeatId\": 5.9}", 1L));
        badRequest(postJson(path, "USER", "{\"scheduleSeatId\": \"6\"}", 1L));

        // 같은 요청을 정수로 보내면 형식은 통과하고, 없는 좌석이라 404다
        postJson(path, "USER", "{\"scheduleSeatId\": 6}", 1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
    }

    @Test
    @DisplayName("좌석 준비(내부 API): configVersion·seatNumber에 소수나 문자열 숫자, isSellable에 문자열 boolean이 오면 400이다")
    void seatSetup() throws Exception {
        String path = "/internal/schedules/{scheduleId}/seat-setups";
        String template = """
                {"requestId":"req-1","scheduleId":1301,"configVersion":%s,"seats":[{"venueSeatId":1,"venueSeatGradeId":7,
                "gradeName":"VIP","basePrice":150000,"isSellable":%s,"section":"A","rowLabel":"1","seatNumber":%s}]}
                """;
        badRequest(postJson(path, "USER", template.formatted("1.9", "true", "1"), 1301L));
        badRequest(postJson(path, "USER", template.formatted("\"1\"", "true", "1"), 1301L));
        badRequest(postJson(path, "USER", template.formatted("1", "\"true\"", "1"), 1301L));
        badRequest(postJson(path, "USER", template.formatted("1", "true", "1.5"), 1301L));

        postJson(path, "USER", template.formatted("1", "true", "1"), 1301L).andExpect(status().isOk());
    }

    @Test
    @DisplayName("예매 결제 준비: userCouponId에 소수나 문자열 숫자가 오면 400이고, 정수면 본문을 통과해 예약 확인까지 간다")
    void checkout() throws Exception {
        String path = "/api/reservations/{reservationId}/payments/checkout";
        badRequest(postJson(path, "USER", "{\"userCouponId\": 1.5, \"pgProvider\": \"TOSS\"}", 999_999L));
        badRequest(postJson(path, "USER", "{\"userCouponId\": \"3\", \"pgProvider\": \"TOSS\"}", 999_999L));

        postJson(path, "USER", "{\"userCouponId\": 3, \"pgProvider\": \"TOSS\"}", 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("쿠폰 생성: 할인액·수량·유효 일수에 소수나 문자열 숫자가 오면 400이다")
    void couponCreate() throws Exception {
        String template = """
                {"name":"쿠폰","discountAmount":%s,"totalQuantity":%s,"issueStartAt":"2099-01-01T00:00:00",
                "issueEndAt":"2099-01-02T00:00:00","validDays":%s}
                """;
        badRequest(postJson("/api/coupons", "MASTER", template.formatted("1000.5", "10", "7")));
        badRequest(postJson("/api/coupons", "MASTER", template.formatted("1000", "\"10\"", "7")));
        badRequest(postJson("/api/coupons", "MASTER", template.formatted("1000", "10", "7.0")));
    }
}
