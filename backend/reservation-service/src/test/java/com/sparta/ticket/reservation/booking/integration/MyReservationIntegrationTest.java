package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 회원이 새로고침하거나 다른 기기에서 열어도 자기 예매와 티켓을 확인할 수 있는지 본다. */
class MyReservationIntegrationTest extends BookingIntegrationTest {

    private static final String LIST = "/api/reservations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private HoldExpiryService holdExpiryService;

    private final Long userId = TestIds.next();

    /** 좌석 두 장, 정가 20,000원인 선점 예약. */
    private Long hold() {
        return hold(userId);
    }

    private Long hold(Long owner) {
        Long firstSeat = TestIds.next();
        Long secondSeat = TestIds.next();
        return transactionTemplate.execute(status -> bookingFacade.hold(
                owner,
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(firstSeat, secondSeat),
                clock.now().plusMinutes(10)));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions detail(Long reservationId) throws Exception {
        return mockMvc.perform(as(get(LIST + "/{id}", reservationId))).andExpect(status().isOk());
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(as(get(LIST + query)));
    }

    private String checkout(Long reservationId, Long userCouponId) throws Exception {
        String body = userCouponId == null
                ? "{\"pgProvider\": \"TOSS\"}"
                : "{\"pgProvider\": \"TOSS\", \"userCouponId\": " + userCouponId + "}";
        String response = mockMvc.perform(as(post(LIST + "/{id}/payments/checkout", reservationId))
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).at("/data/orderId").asText();
    }

    private ResultActions confirm(Long reservationId, String orderId) throws Exception {
        return mockMvc.perform(as(post(LIST + "/{id}/payments/confirm", reservationId))
                .content(objectMapper.writeValueAsString(Map.of("orderId", orderId, "pgAuthToken", "pk-1"))));
    }

    private Long confirmed(Long userCouponId) throws Exception {
        Long reservationId = hold();
        String orderId = checkout(reservationId, userCouponId);
        confirm(reservationId, orderId).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        return reservationId;
    }

    @Test
    @DisplayName("예약 상세는 공연, 회차 시각, 금액과 티켓을 알려 준다")
    void detailShowsTicketInformation() throws Exception {
        Long coupon = coupons.issue(userId, 3_000);

        Long reservationId = confirmed(coupon);

        detail(reservationId)
                .andExpect(jsonPath("$.data.performanceId").value(TestHoldTargets.PERFORMANCE_ID))
                .andExpect(jsonPath("$.data.performanceTitle").value(TestHoldTargets.PERFORMANCE_TITLE))
                .andExpect(jsonPath("$.data.scheduleStartAt").exists())
                .andExpect(jsonPath("$.data.totalPrice").value(20_000))
                .andExpect(jsonPath("$.data.userCouponId").value(coupon))
                .andExpect(jsonPath("$.data.discountAmount").value(3_000))
                .andExpect(jsonPath("$.data.paymentAmount").value(17_000))
                .andExpect(jsonPath("$.data.tickets", hasSize(2)))
                .andExpect(jsonPath("$.data.tickets[0].price").value(10_000))
                .andExpect(jsonPath("$.data.tickets[0].ticketCode").isString());
    }

    @Test
    @DisplayName("결제 승인 응답에도 발급한 티켓 코드가 담긴다")
    void confirmResponseShowsTicketCode() throws Exception {
        Long reservationId = hold();
        String orderId = checkout(reservationId, null);

        confirm(reservationId, orderId)
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.tickets[0].ticketCode").isString());
    }

    @Test
    @DisplayName("쿠폰을 쓰지 않으면 할인액은 없고, 확정 전에는 티켓 코드가 없다")
    void holdHasNoDiscountOrTicketCode() throws Exception {
        Long reservationId = hold();

        detail(reservationId)
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.discountAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.tickets[0].ticketCode").value(nullValue()));
    }

    @Test
    @DisplayName("취소한 예약에는 티켓 코드를 보여 주지 않는다")
    void canceledHidesTicketCode() throws Exception {
        Long reservationId = confirmed(null);

        mockMvc.perform(as(post(LIST + "/{id}/cancel", reservationId)))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        detail(reservationId).andExpect(jsonPath("$.data.tickets[0].ticketCode").value(nullValue()));
    }

    @Test
    @DisplayName("내 예매 목록은 본인 예약만 최신순으로 보여 주고, 공연명과 좌석 수를 담는다")
    void listShowsOnlyMineNewestFirst() throws Exception {
        Long older = hold();
        clock.advance(Duration.ofSeconds(1));
        Long newer = confirmed(null);
        hold(TestIds.next());

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.sort").value("createdAt,DESC"))
                .andExpect(
                        jsonPath("$.data.content[*].reservationId").value(contains(newer.intValue(), older.intValue())))
                .andExpect(jsonPath("$.data.content[0].performanceId").value(TestHoldTargets.PERFORMANCE_ID))
                .andExpect(jsonPath("$.data.content[0].performanceTitle").value(TestHoldTargets.PERFORMANCE_TITLE))
                .andExpect(jsonPath("$.data.content[0].scheduleStartAt").exists())
                .andExpect(jsonPath("$.data.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.content[0].paymentAmount").value(20_000))
                .andExpect(jsonPath("$.data.content[0].seatCount").value(2))
                .andExpect(jsonPath("$.data.content[0].createdAt").exists());
    }

    @Test
    @DisplayName("상태를 지정하지 않으면 결제 없이 끝난 예약은 빼고, 지정하면 그 상태만 보여 준다")
    void listFiltersByStatus() throws Exception {
        Long expired = hold();
        clock.advance(Duration.ofMinutes(11));
        holdExpiryService.expireDue(10, Duration.ofSeconds(5));
        Long active = hold();

        list("").andExpect(jsonPath("$.data.content[*].reservationId").value(contains(active.intValue())));
        list("?status=EXPIRED")
                .andExpect(jsonPath("$.data.content[*].reservationId").value(contains(expired.intValue())));
    }

    @Test
    @DisplayName("같은 시각에 만든 예약은 나중에 만든 것부터 보여 주어 페이지마다 순서가 같다")
    void sameTimeKeepsStableOrder() throws Exception {
        Long first = hold();
        Long second = hold();
        Long third = hold();

        assertThat(ids(page("?size=10&page=0"))).containsExactly(third, second, first);
    }

    @Test
    @DisplayName("정렬할 수 없는 필드나 모르는 상태를 보내면 400이다")
    void rejectsUnknownSortAndStatus() throws Exception {
        list("?sort=userId,DESC").andExpect(status().isBadRequest());
        list("?status=NOPE").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("내 예매 목록은 회원만 쓴다")
    void listIsForUsers() throws Exception {
        mockMvc.perform(get(LIST)
                        .header(UserHeaders.USER_ID, userId.toString())
                        .header(UserHeaders.USERNAME, "seller01")
                        .header(UserHeaders.ROLE, "SELLER"))
                .andExpect(status().isForbidden());
    }

    private JsonNode page(String query) throws Exception {
        return objectMapper.readTree(list(query).andReturn().getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.at("/data/content")
                .forEach(item -> ids.add(item.get("reservationId").asLong()));
        return ids;
    }
}
