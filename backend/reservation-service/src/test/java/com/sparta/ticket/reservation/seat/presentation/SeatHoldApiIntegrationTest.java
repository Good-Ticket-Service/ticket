package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.SimpleErrorCode;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좌석 선점 API(POST /api/seats/schedules/{scheduleId}/holds)를 실제 DB·BookingFacade로 확인한다.
 * 판매 검증만 가짜(FakeSaleValidationPort)로 바꾼다. 공연 서비스 판매 검증 API(#109)가 아직 없고, 거절·장애를 마음대로 만들기 위해서다.
 */
class SeatHoldApiIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 601L;
    private static final Long OTHER_SCHEDULE = 602L;
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

    /** 판매 검증 가짜의 기본 가격(150,000원)과 같은 가격, 구성 버전 2로 좌석을 만든다. */
    private Long seat(Long scheduleId, boolean sellable) {
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        scheduleId,
                        venueSeatId,
                        7L,
                        "VIP",
                        sellable ? FakeSaleValidationPort.DEFAULT_PRICE : null,
                        "A",
                        "1",
                        (int) venueSeatId,
                        sellable,
                        2L))
                .getId();
    }

    private ResultActions hold(Long user, Long scheduleId, Long seatId) throws Exception {
        return mockMvc.perform(post("/api/seats/schedules/{scheduleId}/holds", scheduleId)
                .header(UserHeaders.USER_ID, user.toString())
                .header(UserHeaders.USERNAME, "user" + user)
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduleSeatId\": " + seatId + "}"));
    }

    private Long reservationIdOf(ResultActions result) throws Exception {
        JsonNode body = JSON.readTree(result.andReturn().getResponse().getContentAsString());
        return body.get("data").get("reservationId").asLong();
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reservation_id, hold_expires_at FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private String reservationStatus(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    private Long reservationOnSeat(Long seatId) {
        Object id = seatRow(seatId).get("reservation_id");
        return id == null ? null : ((Number) id).longValue();
    }

    @Test
    @DisplayName("새 선점은 201이고 좌석·예약이 HOLD가 된다. 만료 시각은 +10분이고 둘이 같으며, 공연·판매자·회차 시작 시각·공연명은 판매 검증 값이다")
    void createsHold() throws Exception {
        Long seatId = seat(SCHEDULE, true);

        ResultActions result = hold(USER, SCHEDULE, seatId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.scheduleSeatId").value(seatId))
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.holdExpiresAt").value("2026-11-01T20:10:00+09:00"))
                .andExpect(jsonPath("$.data.basePrice").value(FakeSaleValidationPort.DEFAULT_PRICE));

        Long reservationId = reservationIdOf(result);
        assertThat(seatRow(seatId)).containsEntry("status", "HOLD");
        assertThat(reservationOnSeat(seatId)).isEqualTo(reservationId);
        Map<String, Object> reservation = jdbcTemplate.queryForMap(
                "SELECT status, hold_expires_at, performance_id, seller_id, schedule_start_at, performance_title"
                        + " FROM p_reservations WHERE reservation_id = ?",
                reservationId);
        assertThat(reservation.get("status")).isEqualTo("HOLD");
        assertThat(reservation.get("hold_expires_at")).isEqualTo(seatRow(seatId).get("hold_expires_at"));
        assertThat(((Number) reservation.get("performance_id")).longValue())
                .isEqualTo(FakeSaleValidationPort.PERFORMANCE_ID);
        assertThat(((Number) reservation.get("seller_id")).longValue()).isEqualTo(FakeSaleValidationPort.SELLER_ID);
        assertThat(((Timestamp) reservation.get("schedule_start_at")).toLocalDateTime())
                .isEqualTo(FakeSaleValidationPort.SCHEDULE_START_AT);
        assertThat(reservation.get("performance_title")).isEqualTo(FakeSaleValidationPort.PERFORMANCE_TITLE);
    }

    @Test
    @DisplayName("같은 회원이 같은 좌석을 만료 전에 다시 선점하면 기존 예약을 200으로 돌려주고 새로 만들지 않는다")
    void sameSeatAgainReturnsExisting() throws Exception {
        Long seatId = seat(SCHEDULE, true);
        Long first = reservationIdOf(hold(USER, SCHEDULE, seatId).andExpect(status().isCreated()));

        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.reservationId").value(first));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_reservations", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원의 유효한 선점이나 판매 완료 좌석은 409 SEAT_ALREADY_TAKEN이다")
    void takenSeats() throws Exception {
        Long held = seat(SCHEDULE, true);
        Long sold = seat(SCHEDULE, true);
        hold(OTHER_USER, SCHEDULE, held).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = 9001 WHERE id = ?", sold);

        hold(USER, SCHEDULE, held)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_TAKEN"));
        hold(USER, SCHEDULE, sold)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_TAKEN"));
    }

    @Test
    @DisplayName("판매 제외 좌석은 409 SEAT_NOT_SELLABLE, 좌석 가격·구성 버전이 판매 검증과 다르면 409 SEAT_CONFIG_VERSION_MISMATCH다")
    void seatConditions() throws Exception {
        Long excluded = seat(SCHEDULE, false);
        Long seatId = seat(SCHEDULE, true);

        hold(USER, SCHEDULE, excluded)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_SELLABLE"));

        saleValidation.price(160_000);
        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_CONFIG_VERSION_MISMATCH"));

        saleValidation.reset();
        saleValidation.configVersion(3L);
        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_CONFIG_VERSION_MISMATCH"));
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("판매 검증 거절의 reasonCode를 그대로 외부 code로 쓴다. 좌석 문제는 409, 판매 기간·공연 상태는 400이다")
    void rejectionReasonCode() throws Exception {
        Long seatId = seat(SCHEDULE, true);

        saleValidation.reject("SALES_CLOSED");
        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SALES_CLOSED"));

        saleValidation.reject("SEAT_NOT_SELLABLE");
        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_SELLABLE"));
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("없는 좌석·다른 회차 좌석은 404 SEAT_NOT_FOUND이고 판매 검증을 부르지 않는다. 판매 검증의 404는 그대로 전달한다")
    void notFound() throws Exception {
        Long otherScheduleSeat = seat(OTHER_SCHEDULE, true);
        Long seatId = seat(SCHEDULE, true);

        hold(USER, SCHEDULE, otherScheduleSeat)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        hold(USER, SCHEDULE, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        assertThat(saleValidation.calls()).isZero();

        saleValidation.fail(new BusinessException(
                new SimpleErrorCode(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "회차를 찾을 수 없습니다.")));
        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 회원의 만료된 선점을 가져오면 옛 예약은 EXPIRED, 그 예약의 다른 좌석도 함께 반환돼 이어서 선점할 수 있다")
    void takesExpiredHoldAndReleasesAllSeatsOfOldReservation() throws Exception {
        Long seat5 = seat(SCHEDULE, true);
        Long seat9 = seat(SCHEDULE, true);
        // 옛 예약 R1이 좌석 5·9를 잡은 채 만료됐다(만료 스케줄러는 아직 돌지 않음)
        LocalDateTime expiresAt = NOW.plusMinutes(10);
        Long r1 = transactionTemplate.execute(status ->
                bookingFacade.hold(9L, TestHoldTargets.of(SCHEDULE), TestSeats.priced(seat5, seat9), expiresAt));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id IN (?, ?)",
                r1,
                expiresAt,
                seat5,
                seat9);
        clock.advance(Duration.ofMinutes(11));

        Long newReservation = reservationIdOf(hold(USER, SCHEDULE, seat9).andExpect(status().isCreated()));

        assertThat(reservationStatus(r1)).isEqualTo("EXPIRED");
        assertThat(seatRow(seat9)).containsEntry("status", "HOLD");
        assertThat(reservationOnSeat(seat9)).isEqualTo(newReservation);
        assertThat(seatRow(seat5)).containsEntry("status", "AVAILABLE");
        assertThat(reservationOnSeat(seat5)).isNull();
        hold(OTHER_USER, SCHEDULE, seat5).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("만료 시각이 지났어도 그 예약이 결제 중이면 409 SEAT_ALREADY_TAKEN이고 옛 예약·좌석을 바꾸지 않는다")
    void expiredButPaymentInProgress() throws Exception {
        Long seatId = seat(SCHEDULE, true);
        Long paying = reservationIdOf(hold(OTHER_USER, SCHEDULE, seatId).andExpect(status().isCreated()));
        jdbcTemplate.update(
                "UPDATE p_reservations SET status = 'PAYMENT_IN_PROGRESS' WHERE reservation_id = ?", paying);
        clock.advance(Duration.ofMinutes(11));

        hold(USER, SCHEDULE, seatId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_TAKEN"));

        assertThat(reservationStatus(paying)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(seatRow(seatId)).containsEntry("status", "HOLD");
        assertThat(reservationOnSeat(seatId)).isEqualTo(paying);
    }

    @Test
    @DisplayName("이 회원의 만료된 선점이 다른 좌석에 있으면 그 예약을 만료시키고 좌석을 돌려준 뒤 새로 선점한다")
    void ownExpiredHoldIsCleanedUp() throws Exception {
        Long oldSeat = seat(SCHEDULE, true);
        Long newSeat = seat(SCHEDULE, true);
        Long old = reservationIdOf(hold(USER, SCHEDULE, oldSeat).andExpect(status().isCreated()));
        clock.advance(Duration.ofMinutes(11));

        hold(USER, SCHEDULE, newSeat).andExpect(status().isCreated());

        assertThat(reservationStatus(old)).isEqualTo("EXPIRED");
        assertThat(seatRow(oldSeat)).containsEntry("status", "AVAILABLE");
        assertThat(seatRow(newSeat)).containsEntry("status", "HOLD");
    }

    @Test
    @DisplayName("이 회차에 유효한 예약이 있으면 다른 좌석 선점은 409 PURCHASE_LIMIT_EXCEEDED다")
    void purchaseLimit() throws Exception {
        Long first = seat(SCHEDULE, true);
        Long second = seat(SCHEDULE, true);
        hold(USER, SCHEDULE, first).andExpect(status().isCreated());

        hold(USER, SCHEDULE, second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(seatRow(second)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("scheduleSeatId가 없으면 400 VALIDATION_ERROR다")
    void validation() throws Exception {
        mockMvc.perform(post("/api/seats/schedules/{scheduleId}/holds", SCHEDULE)
                        .header(UserHeaders.USER_ID, USER.toString())
                        .header(UserHeaders.USERNAME, "user")
                        .header(UserHeaders.ROLE, "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
