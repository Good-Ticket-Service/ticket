package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좌석 선점 해제 API(DELETE /api/seats/schedules/{scheduleId}/holds/{reservationId})를 실제 DB·BookingFacade로 확인한다.
 * 선점은 선점 API로 만들고, 정상 경로로는 만들 수 없는 상태(여러 좌석 예약, 좌석만 남은 끝난 예약)는 직접 만든다.
 */
class SeatReleaseApiIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 801L;
    private static final Long OTHER_SCHEDULE = 802L;
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        saleValidation.reset();
        clock.set(NOW);
    }

    /** 판매 검증 가짜의 기본 가격과 같은 가격, 구성 버전 2로 판매 대상 좌석을 만든다. */
    private Long seat(Long scheduleId) {
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

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Long user) {
        return request.header(UserHeaders.USER_ID, user.toString())
                .header(UserHeaders.USERNAME, "user" + user)
                .header(UserHeaders.ROLE, "USER");
    }

    private Long hold(Long user, Long seatId) throws Exception {
        String body = mockMvc.perform(as(post("/api/seats/schedules/{scheduleId}/holds", SCHEDULE), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleSeatId\": " + seatId + "}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(body).get("data").get("reservationId").asLong();
    }

    private ResultActions release(Long user, Long scheduleId, Long reservationId) throws Exception {
        return mockMvc.perform(
                as(delete("/api/seats/schedules/{scheduleId}/holds/{reservationId}", scheduleId, reservationId), user));
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, hold_expires_at FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private String reservationStatus(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    private void setStatus(Long reservationId, String status) {
        jdbcTemplate.update("UPDATE p_reservations SET status = ? WHERE reservation_id = ?", status, reservationId);
    }

    @Test
    @DisplayName("만료 전 선점을 해제하면 204, 예약은 RELEASED, 좌석은 AVAILABLE이고 같은 회차의 다른 좌석을 바로 선점할 수 있다")
    void releasesLiveHold() throws Exception {
        Long first = seat(SCHEDULE);
        Long second = seat(SCHEDULE);
        Long reservationId = hold(USER, first);

        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());

        assertThat(reservationStatus(reservationId)).isEqualTo("RELEASED");
        assertThat(seatRow(first))
                .containsEntry("status", "AVAILABLE")
                .containsEntry("reservation_id", null)
                .containsEntry("hold_expires_at", null);
        // 1인 1장 제한이 풀렸다
        hold(USER, second);
    }

    @Test
    @DisplayName("만료 후 선점을 해제하면 204, 예약은 EXPIRED, 좌석은 AVAILABLE이다")
    void releasesExpiredHold() throws Exception {
        Long seatId = seat(SCHEDULE);
        Long reservationId = hold(USER, seatId);
        clock.advance(Duration.ofMinutes(11));

        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());

        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("같은 해제를 반복해도 204이고, 그 사이 다른 회원이 잡은 좌석은 건드리지 않는다")
    void repeatedReleaseKeepsOthersSeat() throws Exception {
        Long seatId = seat(SCHEDULE);
        Long reservationId = hold(USER, seatId);
        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());
        Long others = hold(OTHER_USER, seatId);

        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());

        assertThat(seatRow(seatId)).containsEntry("status", "HOLD");
        assertThat(((Number) seatRow(seatId).get("reservation_id")).longValue()).isEqualTo(others);
        assertThat(reservationStatus(others)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("좌석 여러 장을 잡은 예약을 해제하면 모든 좌석을 반환한다")
    void releasesAllSeatsOfReservation() throws Exception {
        Long seat5 = seat(SCHEDULE);
        Long seat9 = seat(SCHEDULE);
        LocalDateTime expiresAt = NOW.plusMinutes(10);
        Long reservationId = transactionTemplate.execute(status ->
                bookingFacade.hold(USER, TestHoldTargets.of(SCHEDULE), TestSeats.priced(seat5, seat9), expiresAt));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id IN (?, ?)",
                reservationId,
                expiresAt,
                seat5,
                seat9);

        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());

        assertThat(seatRow(seat5)).containsEntry("status", "AVAILABLE");
        assertThat(seatRow(seat9)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("이미 끝난 예약(ALREADY_CLOSED)에 좌석이 남아 있으면 그 좌석을 반환하고 204다")
    void alreadyClosedReleasesLeftoverSeat() throws Exception {
        Long seatId = seat(SCHEDULE);
        Long reservationId = hold(USER, seatId);
        // 정상 경로로는 생기지 않는 상태: 예약만 EXPIRED, 좌석은 그 예약의 HOLD 그대로
        setStatus(reservationId, "EXPIRED");

        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());

        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("끝난 선점의 좌석이 SOLD로 남아 있으면 204이지만 그 좌석은 반환하지 않는다")
    void alreadyClosedKeepsSoldSeat() throws Exception {
        Long seatId = seat(SCHEDULE);
        Long reservationId = hold(USER, seatId);
        // 정상 경로로는 생기지 않는 상태: 예약은 EXPIRED인데 좌석은 그 예약의 SOLD
        setStatus(reservationId, "EXPIRED");
        jdbcTemplate.update("UPDATE p_schedule_seats SET status = 'SOLD', hold_expires_at = NULL WHERE id = ?", seatId);

        release(USER, SCHEDULE, reservationId).andExpect(status().isNoContent());

        assertThat(seatRow(seatId)).containsEntry("status", "SOLD");
        assertThat(((Number) seatRow(seatId).get("reservation_id")).longValue()).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("결제 중·확정 예약은 409 INVALID_STATUS_TRANSITION이고 좌석을 바꾸지 않는다")
    void notReleasable() throws Exception {
        Long paying = seat(SCHEDULE);
        Long sold = seat(SCHEDULE);
        Long payingReservation = hold(USER, paying);
        setStatus(payingReservation, "PAYMENT_IN_PROGRESS");
        Long confirmed = hold(OTHER_USER, sold);
        setStatus(confirmed, "CONFIRMED");
        jdbcTemplate.update("UPDATE p_schedule_seats SET status = 'SOLD', hold_expires_at = NULL WHERE id = ?", sold);

        release(USER, SCHEDULE, payingReservation)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        release(OTHER_USER, SCHEDULE, confirmed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        assertThat(seatRow(paying)).containsEntry("status", "HOLD");
        assertThat(seatRow(sold)).containsEntry("status", "SOLD");
    }

    @Test
    @DisplayName("없는 예약·다른 회차 예약은 404 RESERVATION_NOT_FOUND, 남의 예약은 403 FORBIDDEN이고 좌석을 바꾸지 않는다")
    void notFoundAndForbidden() throws Exception {
        Long seatId = seat(SCHEDULE);
        Long reservationId = hold(USER, seatId);

        release(USER, SCHEDULE, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        release(USER, OTHER_SCHEDULE, reservationId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        release(OTHER_USER, SCHEDULE, reservationId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(reservationStatus(reservationId)).isEqualTo("HOLD");
        assertThat(seatRow(seatId)).containsEntry("status", "HOLD");
    }
}
