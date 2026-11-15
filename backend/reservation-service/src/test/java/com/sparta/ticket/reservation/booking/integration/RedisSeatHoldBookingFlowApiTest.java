package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.infrastructure.seat.SeatFacadeAdapter;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.seat.application.SeatMapSnapshotLoader;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * REDIS 모드(#449)에서 예매 전체 흐름을 HTTP API로 끝까지 확인한다. 좌석 선점부터 결제하기·결제 준비(쿠폰)·승인·확정·취소까지
 * 실제 좌석·예매·쿠폰 모듈과 DB·Redis를 쓰고, 결제 서버만 가짜다. 결제하기 뒤에는 DB 방식 선점과 같은 경로를 타야 한다.
 */
class RedisSeatHoldBookingFlowApiTest extends BookingIntegrationTest {

    private static final int DISCOUNT = 3_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private SeatMapSnapshotLoader snapshotLoader;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableSeatHoldModeSwitch modeSwitch;

    @Autowired
    private StringRedisTemplate redis;

    private final Long user = TestIds.next();
    private final Long other = TestIds.next();
    private final Long scheduleId = TestIds.next();
    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        Set<String> keys = redis.keys("seathold:{s" + scheduleId + "}:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        seats.useReal(seatFacadeAdapter);
        saleValidation.reset();
        modeSwitch.set(SeatHoldMode.REDIS);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
        seats.reset();
    }

    private Long seat() {
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        scheduleId,
                        venueSeatId,
                        7L,
                        "VIP",
                        FakeSaleValidationPort.DEFAULT_PRICE,
                        "A",
                        "1",
                        (int) venueSeatId,
                        true,
                        2L))
                .getId();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Long userId) {
        return request.header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "user" + userId)
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return objectMapper
                .readTree(actions.andReturn().getResponse().getContentAsString())
                .get("data");
    }

    private ResultActions hold(Long userId, Long seatId) throws Exception {
        return mockMvc.perform(as(post("/api/seats/schedules/{scheduleId}/holds", scheduleId), userId)
                .content("{\"scheduleSeatId\": " + seatId + "}"));
    }

    private ResultActions convert(Long userId, String holdId) throws Exception {
        return mockMvc.perform(
                as(post("/api/seats/schedules/{scheduleId}/holds/{holdId}/reservation", scheduleId, holdId), userId));
    }

    private ResultActions checkout(Long userId, long reservationId, Long userCouponId) throws Exception {
        String body = userCouponId == null
                ? "{\"pgProvider\": \"TOSS\"}"
                : "{\"pgProvider\": \"TOSS\", \"userCouponId\": " + userCouponId + "}";
        return mockMvc.perform(as(post("/api/reservations/{id}/payments/checkout", reservationId), userId)
                .content(body));
    }

    private ResultActions confirm(Long userId, long reservationId, String orderId) throws Exception {
        return mockMvc.perform(as(post("/api/reservations/{id}/payments/confirm", reservationId), userId)
                .content(objectMapper.writeValueAsString(Map.of("orderId", orderId, "pgAuthToken", "pk-" + orderId))));
    }

    private boolean selectable(Long seatId) {
        return snapshotLoader.load(scheduleId).result().seats().stream()
                .filter(seat -> seat.scheduleSeatId().equals(seatId))
                .map(SelectableSeatResult::selectable)
                .findFirst()
                .orElseThrow();
    }

    private String seatStatus(Long seatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM p_schedule_seats WHERE id = ?", String.class, seatId);
    }

    private int reservations() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class);
    }

    @Test
    @DisplayName("선점 → 조회 → 결제하기 → 쿠폰 결제 준비 → 승인 → 확정 → 취소 → 다른 회원 재선점까지 이어진다")
    void fullFlow() throws Exception {
        Long seatId = seat();
        Long coupon = coupons.issue(user, DISCOUNT);

        // 선점: Redis에만 있고 예약은 없다. 배치도에는 선택 불가로 보인다
        String holdId = data(hold(user, seatId).andExpect(status().isCreated()))
                .get("holdId")
                .asText();
        assertThat(reservations()).isZero();
        assertThat(selectable(seatId)).isFalse();
        mockMvc.perform(as(get("/api/seats/schedules/{scheduleId}/holds/{holdId}", scheduleId, holdId), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HELD"));

        // 결제하기: 예약이 생기고, 그 뒤는 예약 API를 쓴다
        long reservationId = data(convert(user, holdId).andExpect(status().isCreated()))
                .get("reservationId")
                .asLong();
        mockMvc.perform(as(get("/api/reservations/{id}", reservationId), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.totalPrice").value(FakeSaleValidationPort.DEFAULT_PRICE))
                .andExpect(jsonPath("$.data.performanceTitle").value(FakeSaleValidationPort.PERFORMANCE_TITLE));
        assertThat(seatStatus(seatId)).isEqualTo("HOLD");
        assertThat(selectable(seatId)).isFalse();

        // 결제 준비(쿠폰)와 승인
        String orderId = data(checkout(user, reservationId, coupon).andExpect(status().isOk()))
                .get("orderId")
                .asText();
        assertThat(payment.prepared.get(payment.prepared.size() - 1).discountAmount())
                .isEqualTo(DISCOUNT);
        confirm(user, reservationId, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.tickets[0].ticketCode").isString());
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
        assertThat(coupons.statusOf(coupon)).isEqualTo("USED");

        // 확정된 좌석은 다른 회원이 잡지 못하고, 확정한 회원은 다른 좌석을 잡지 못한다(1장 제한)
        hold(other, seatId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_TAKEN"));
        hold(user, seat())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));

        // 취소하면 좌석과 쿠폰이 돌아오고, 다른 회원이 바로 잡을 수 있다
        mockMvc.perform(as(post("/api/reservations/{id}/cancel", reservationId), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        hold(other, seatId).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("결제하기 전에 해제하면 예약 흔적 없이 좌석이 풀리고, 내 예매 목록에도 없다")
    void releaseBeforeConvert() throws Exception {
        Long seatId = seat();
        String holdId = data(hold(user, seatId)).get("holdId").asText();

        mockMvc.perform(as(delete("/api/seats/schedules/{scheduleId}/holds/{id}", scheduleId, holdId), user))
                .andExpect(status().isNoContent());

        assertThat(reservations()).isZero();
        assertThat(selectable(seatId)).isTrue();
        mockMvc.perform(as(get("/api/reservations"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
        hold(other, seatId).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("결제하기 뒤 결제가 실패하면 HOLD로 돌아오고 쿠폰은 유지되며, 다시 결제하면 확정된다")
    void paymentFailureThenRetry() throws Exception {
        Long seatId = seat();
        Long coupon = coupons.issue(user, DISCOUNT);
        String holdId = data(hold(user, seatId)).get("holdId").asText();
        long reservationId = data(convert(user, holdId)).get("reservationId").asLong();

        payment.confirmReturns(ConfirmOutcome.failed());
        String firstOrder =
                data(checkout(user, reservationId, coupon)).get("orderId").asText();
        confirm(user, reservationId, firstOrder)
                .andExpect(jsonPath("$.data.status").value("HOLD"));
        assertThat(seatStatus(seatId)).isEqualTo("HOLD");
        assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");

        payment.confirmReturns(ConfirmOutcome.succeeded());
        String secondOrder =
                data(checkout(user, reservationId, coupon)).get("orderId").asText();
        confirm(user, reservationId, secondOrder)
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
        assertThat(coupons.statusOf(coupon)).isEqualTo("USED");
    }

    @Test
    @DisplayName("Redis 선점 뒤 회차가 취소되면, 결제하기로 예약은 생기지만 결제 준비가 409로 막혀 돈이 나가지 않는다")
    void scheduleCanceledBeforeConvert() throws Exception {
        Long seatId = seat();
        String holdId = data(hold(user, seatId)).get("holdId").asText();
        jdbcTemplate.update(
                "INSERT INTO p_schedule_cancellations (schedule_id, event_id, canceled_at, received_at, status)"
                        + " VALUES (?, 'evt-1', ?, ?, 'RECEIVED')",
                scheduleId,
                clock.now(),
                clock.now());

        long reservationId = data(convert(user, holdId).andExpect(status().isCreated()))
                .get("reservationId")
                .asLong();

        checkout(user, reservationId, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(payment.prepared).isEmpty();
    }

    @Test
    @DisplayName("선점 시간이 지난 뒤의 결제하기는 409 HOLD_EXPIRED이고 예약을 만들지 않는다")
    void convertAfterExpiry() throws Exception {
        Long seatId = seat();
        String holdId = data(hold(user, seatId)).get("holdId").asText();
        clock.advance(Duration.ofMinutes(11));

        convert(user, holdId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(reservations()).isZero();
    }
}
