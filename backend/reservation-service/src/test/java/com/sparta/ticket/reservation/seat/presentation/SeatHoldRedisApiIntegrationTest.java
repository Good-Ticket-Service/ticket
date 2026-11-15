package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * REDIS 모드 선점 API(#449)를 실제 DB·Redis로 확인한다. 선점 → 조회 → 결제하기(예약으로 바꾸기) → 해제의 상태 코드와 응답 필드를 본다.
 */
class SeatHoldRedisApiIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 804L;
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableSeatHoldModeSwitch modeSwitch;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        Set<String> keys = redis.keys("seathold:{s" + SCHEDULE + "}:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        saleValidation.reset();
        clock.set(NOW);
        modeSwitch.set(SeatHoldMode.REDIS);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
    }

    private Long seat() {
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE,
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

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Long user) {
        return request.header(UserHeaders.USER_ID, user.toString())
                .header(UserHeaders.USERNAME, "user" + user)
                .header(UserHeaders.ROLE, "USER");
    }

    private ResultActions hold(Long user, Long seatId) throws Exception {
        return mockMvc.perform(as(post("/api/seats/schedules/{scheduleId}/holds", SCHEDULE), user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduleSeatId\": " + seatId + "}"));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString())
                .get("data");
    }

    private String holdId(Long user, Long seatId) throws Exception {
        return data(hold(user, seatId).andExpect(status().isCreated()))
                .get("holdId")
                .asText();
    }

    private ResultActions view(Long user, String holdId) throws Exception {
        return mockMvc.perform(as(get("/api/seats/schedules/{scheduleId}/holds/{holdId}", SCHEDULE, holdId), user));
    }

    private ResultActions convert(Long user, String holdId) throws Exception {
        return mockMvc.perform(
                as(post("/api/seats/schedules/{scheduleId}/holds/{holdId}/reservation", SCHEDULE, holdId), user));
    }

    private ResultActions release(Long user, String id) throws Exception {
        return mockMvc.perform(as(delete("/api/seats/schedules/{scheduleId}/holds/{id}", SCHEDULE, id), user));
    }

    @Test
    @DisplayName("선점은 201과 holdId, reservationId는 비어 있다. 조회는 HELD와 결제 화면 정보를 준다")
    void holdAndView() throws Exception {
        Long seatId = seat();

        hold(USER, seatId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.holdId").isString())
                .andExpect(jsonPath("$.data.reservationId").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.holdExpiresAt").value("2026-11-01T20:10:00+09:00"));
        String holdId = redis.opsForValue().get("seathold:{s" + SCHEDULE + "}:user:" + USER);

        view(USER, holdId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdId").value(holdId))
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andExpect(jsonPath("$.data.scheduleSeatId").value(seatId))
                .andExpect(jsonPath("$.data.basePrice").value(FakeSaleValidationPort.DEFAULT_PRICE))
                .andExpect(jsonPath("$.data.performanceId").value(FakeSaleValidationPort.PERFORMANCE_ID))
                .andExpect(jsonPath("$.data.performanceTitle").value(FakeSaleValidationPort.PERFORMANCE_TITLE))
                .andExpect(jsonPath("$.data.genre").value(FakeSaleValidationPort.GENRE))
                .andExpect(jsonPath("$.data.scheduleStartAt").value("2026-11-30T19:00:00+09:00"))
                .andExpect(jsonPath("$.data.holdExpiresAt").value("2026-11-01T20:10:00+09:00"))
                .andExpect(jsonPath("$.data.reservationId").doesNotExist());
    }

    @Test
    @DisplayName("남의 선점·없는 선점·형식이 다른 holdId 조회는 404 HOLD_NOT_FOUND다")
    void viewNotFound() throws Exception {
        String holdId = holdId(USER, seat());

        view(OTHER_USER, holdId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        view(USER, "h_" + "0".repeat(32)).andExpect(status().isNotFound());
        view(USER, "h_bad}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("결제하기는 201과 예약 ID, 다시 부르면 같은 예약으로 200이다. 조회는 CONVERTED와 예약 ID를 준다")
    void convertFlow() throws Exception {
        String holdId = holdId(USER, seat());

        JsonNode created = data(convert(USER, holdId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reservationId").isNumber())
                .andExpect(jsonPath("$.data.holdId").doesNotExist())
                .andExpect(jsonPath("$.data.holdExpiresAt").value("2026-11-01T20:10:00+09:00")));
        long reservationId = created.get("reservationId").asLong();

        convert(USER, holdId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId").value(reservationId));
        view(USER, holdId)
                .andExpect(jsonPath("$.data.status").value("CONVERTED"))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("holdId 해제는 Redis만 지우고 204, 다시 해도 204, 남의 선점은 403이다")
    void releaseRedisHold() throws Exception {
        Long seatId = seat();
        String holdId = holdId(USER, seatId);

        release(OTHER_USER, holdId).andExpect(status().isForbidden());
        release(USER, holdId).andExpect(status().isNoContent());
        release(USER, holdId).andExpect(status().isNoContent());

        view(USER, holdId).andExpect(status().isNotFound());
        hold(OTHER_USER, seatId).andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("예약으로 바꾼 holdId를 해제하면 그 예약을 해제한다")
    void releaseConvertedHold() throws Exception {
        String holdId = holdId(USER, seat());
        long reservationId = data(convert(USER, holdId)).get("reservationId").asLong();

        release(USER, holdId).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId))
                .isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("숫자 경로는 지금처럼 예약 해제이고, 숫자도 holdId도 아니면 400이다")
    void releasePathKinds() throws Exception {
        String holdId = holdId(USER, seat());
        long reservationId = data(convert(USER, holdId)).get("reservationId").asLong();

        release(USER, String.valueOf(reservationId)).andExpect(status().isNoContent());
        release(USER, "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @DisplayName("스위치가 DB면 선점 응답에 holdId가 없고 예약 ID가 있다")
    void dbModeResponse() throws Exception {
        modeSwitch.set(SeatHoldMode.DB);

        hold(USER, seat())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reservationId").isNumber())
                .andExpect(jsonPath("$.data.holdId").doesNotExist());
    }
}
