package com.sparta.ticket.reservation.seat.presentation;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** GET /api/seats/schedules/{scheduleId}를 실제 DB와 BookingFacade로 확인한다. */
class SelectableSeatApiIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 501L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long nextVenueSeatId = 1000;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(NOW);
    }

    private Long seat(Long scheduleId, boolean sellable) {
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        scheduleId, nextVenueSeatId++, 7L, "VIP", sellable ? 150_000 : null, "A", "1", 1, sellable, 2L))
                .getId();
    }

    private void setHold(Long seatId, Long reservationId, LocalDateTime holdExpiresAt) {
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                reservationId,
                holdExpiresAt,
                seatId);
    }

    /** 실제 예약을 만들어 결제 중으로 바꾼다. 결제 중 여부는 booking의 예약 상태로 판단하기 때문이다. */
    private Long paymentInProgressReservation(Long seatId) {
        Long reservationId = transactionTemplate.execute(status ->
                bookingFacade.hold(77L, TestHoldTargets.of(SCHEDULE), TestSeats.priced(seatId), NOW.minusMinutes(1)));
        jdbcTemplate.update(
                "UPDATE p_reservations SET status = 'PAYMENT_IN_PROGRESS' WHERE reservation_id = ?", reservationId);
        return reservationId;
    }

    @Test
    @DisplayName("판정 6가지를 응답하고, 다른 회원의 예약·만료 시각은 내보내지 않는다. asOf는 +09:00이다")
    void selectableSeats() throws Exception {
        Long available = seat(SCHEDULE, true);
        Long liveHold = seat(SCHEDULE, true);
        Long expiredPaying = seat(SCHEDULE, true);
        Long expiredAbandoned = seat(SCHEDULE, true);
        Long sold = seat(SCHEDULE, true);
        Long unsellable = seat(SCHEDULE, false);
        setHold(liveHold, 9001L, NOW.plusSeconds(1));
        setHold(expiredPaying, paymentInProgressReservation(expiredPaying), NOW.minusMinutes(1));
        setHold(expiredAbandoned, 9002L, NOW);
        jdbcTemplate.update("UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = 9003 WHERE id = ?", sold);

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(SCHEDULE))
                .andExpect(jsonPath("$.data.configVersion").value(2))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-01T20:00:00+09:00"))
                .andExpect(jsonPath("$.data.seats.length()").value(6))
                .andExpect(jsonPath("$.data.seats[0].scheduleSeatId").value(available))
                .andExpect(jsonPath("$.data.seats[0].selectable").value(true))
                .andExpect(jsonPath("$.data.seats[1].selectable").value(false))
                .andExpect(jsonPath("$.data.seats[2].scheduleSeatId").value(expiredPaying))
                .andExpect(jsonPath("$.data.seats[2].selectable").value(false))
                .andExpect(jsonPath("$.data.seats[3].scheduleSeatId").value(expiredAbandoned))
                .andExpect(jsonPath("$.data.seats[3].selectable").value(true))
                .andExpect(jsonPath("$.data.seats[4].selectable").value(false))
                .andExpect(jsonPath("$.data.seats[5].scheduleSeatId").value(unsellable))
                .andExpect(jsonPath("$.data.seats[5].selectable").value(false))
                // 가격 없는 등급의 판매 제외 좌석: 키는 남기고 값만 null (doesNotExist는 null 값도 통과해 구분하지 못한다)
                .andExpect(jsonPath("$.data.seats[5]", hasKey("basePrice")))
                .andExpect(jsonPath("$.data.seats[5].basePrice").value(nullValue()))
                .andExpect(jsonPath("$.data.seats[1].reservationId").doesNotExist())
                .andExpect(jsonPath("$.data.seats[1].holdExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.data.seats[0].section").value("A"))
                .andExpect(jsonPath("$.data.seats[0].gradeName").value("VIP"));
    }

    @Test
    @DisplayName("다른 회차 좌석과 논리 삭제된 좌석은 응답에 없다")
    void onlyThisSchedule() throws Exception {
        Long mine = seat(SCHEDULE, true);
        seat(SCHEDULE + 1, true);
        Long deleted = seat(SCHEDULE, true);
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET deleted_at = ?, deleted_by = 'SYSTEM' WHERE id = ?", NOW, deleted);

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats.length()").value(1))
                .andExpect(jsonPath("$.data.seats[0].scheduleSeatId").value(mine));
    }

    @Test
    @DisplayName("좌석이 하나도 없는 회차는 404 SEAT_NOT_FOUND다")
    void noSeats() throws Exception {
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
    }
}
