package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
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

    @Autowired
    private MutableTicker ticker;

    @Autowired
    private DataSource dataSource;

    private long nextVenueSeatId = 1000;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(NOW);
        // 배치도 캐시는 컨텍스트 하나를 모든 테스트가 같이 쓴다. 앞 테스트가 같은 회차를 캐시해 두었을 수 있어 TTL을 넘긴다
        ticker.advance(Duration.ofMinutes(1));
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

    @Test
    @DisplayName("TTL 안에서는 좌석 상태가 바뀌어도 같은 스냅샷(같은 asOf)을 주고, TTL이 지나면 바뀐 상태를 준다")
    void snapshotWithinTtl() throws Exception {
        Long target = seat(SCHEDULE, true);
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(jsonPath("$.data.seats[0].selectable").value(true))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-01T20:00:00+09:00"));

        setHold(target, 9001L, NOW.plusMinutes(10));
        clock.set(NOW.plusSeconds(1));
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(jsonPath("$.data.seats[0].selectable").value(true))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-01T20:00:00+09:00"));

        ticker.advance(Duration.ofSeconds(1));
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(jsonPath("$.data.seats[0].selectable").value(false))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-01T20:00:01+09:00"));
    }

    @Test
    @DisplayName("404 SEAT_NOT_FOUND는 캐시하지 않아 좌석이 준비되면 TTL 안에서도 바로 보인다")
    void notFoundIsNotCached() throws Exception {
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE)).andExpect(status().isNotFound());

        seat(SCHEDULE, true);

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats.length()").value(1));
    }

    @Test
    @DisplayName("캐시에서 답할 때는 DB 연결을 쓰지 않는다: 풀의 연결을 모두 잡아 둬도 같은 회차 조회가 성공한다")
    void cacheHitUsesNoConnection() throws Exception {
        seat(SCHEDULE, true);
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE)).andExpect(status().isOk());

        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        try {
            // 연결을 하나라도 쓰면 풀이 비어 연결 획득 제한 시간까지 기다리다 실패한다
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(pool.getConnection());
            }
            mockMvc.perform(get("/api/seats/schedules/{scheduleId}", SCHEDULE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.seats.length()").value(1));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    private String body(String path) throws Exception {
        return mockMvc.perform(get(path, SCHEDULE))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("layout·status는 기존 조회와 같은 스냅샷이다: 좌석 순서가 같고, i번째 비트가 기존 조회 i번째 좌석의 selectable과 같다")
    void layoutAndStatusMatchLegacy() throws Exception {
        Long available = seat(SCHEDULE, true);
        Long liveHold = seat(SCHEDULE, true);
        Long expiredPaying = seat(SCHEDULE, true);
        Long expiredAbandoned = seat(SCHEDULE, true);
        Long sold = seat(SCHEDULE, true);
        seat(SCHEDULE, false);
        setHold(liveHold, 9001L, NOW.plusSeconds(1));
        setHold(expiredPaying, paymentInProgressReservation(expiredPaying), NOW.minusMinutes(1));
        setHold(expiredAbandoned, 9002L, NOW);
        jdbcTemplate.update("UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = 9003 WHERE id = ?", sold);

        String legacy = body("/api/seats/schedules/{scheduleId}");
        String layout = body("/api/seats/schedules/{scheduleId}/layout");
        String status = body("/api/seats/schedules/{scheduleId}/status");

        List<Integer> legacyIds = JsonPath.read(legacy, "$.data.seats[*].scheduleSeatId");
        List<Integer> layoutIds = JsonPath.read(layout, "$.data.seats[*].scheduleSeatId");
        List<Boolean> legacySelectable = JsonPath.read(legacy, "$.data.seats[*].selectable");
        assertThat(layoutIds).isEqualTo(legacyIds).hasSize(6).first().isEqualTo(available.intValue());

        byte[] bits = Base64.getDecoder().decode((String) JsonPath.read(status, "$.data.selectableBitmap"));
        List<Boolean> decoded = new ArrayList<>();
        for (int i = 0; i < legacySelectable.size(); i++) {
            decoded.add(((bits[i >> 3] >> (i & 7)) & 1) == 1);
        }
        assertThat(decoded).isEqualTo(legacySelectable).containsExactly(true, false, false, true, false, false);

        assertThat((Integer) JsonPath.read(status, "$.data.seatCount")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(layout, "$.data.configVersion")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(status, "$.data.configVersion")).isEqualTo(2);
        assertThat((String) JsonPath.read(status, "$.data.asOf"))
                .isEqualTo(JsonPath.read(legacy, "$.data.asOf"))
                .isEqualTo("2026-11-01T20:00:00+09:00");
        // layout에는 판정을 넣지 않고, 공연장 좌석 배치(P20)와 짝짓는 venueSeatId는 넣는다
        assertThat((List<?>) JsonPath.read(layout, "$.data.seats[?(@.selectable)]"))
                .isEmpty();
        assertThat((Integer) JsonPath.read(layout, "$.data.seats[0].venueSeatId"))
                .isNotNull();
    }

    @Test
    @DisplayName("layout은 ETag(configVersion)와 private·no-cache를 주고, 같은 ETag로 다시 오면 본문 없이 304다")
    void layoutEtag() throws Exception {
        seat(SCHEDULE, true);

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));

        for (String same : List.of("\"2\"", "W/\"2\"", "\"1\", \"2\"")) {
            mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE)
                            .header("If-None-Match", same))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string("ETag", "\"2\""))
                    .andExpect(header().string("Cache-Control", containsString("no-cache")))
                    .andExpect(content().string(""));
        }

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE)
                        .header("If-None-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats.length()").value(1));
    }

    @Test
    @DisplayName("layout의 ETag 헤더는 200과 304 모두 한 줄이다")
    void layoutEtagHeaderOnce() throws Exception {
        seat(SCHEDULE, true);

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeaders("ETag")).containsExactly("\"2\""));

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE)
                        .header("If-None-Match", "\"2\""))
                .andExpect(status().isNotModified())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeaders("ETag")).containsExactly("\"2\""));
    }

    @Test
    @DisplayName("좌석 준비로 configVersion이 바뀌면 이전 ETag로 와도 200과 새 ETag를 준다")
    void layoutEtagChangesWithConfigVersion() throws Exception {
        seat(SCHEDULE, true);
        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE))
                .andExpect(header().string("ETag", "\"2\""));

        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET config_version = 3, base_price = 160000 WHERE schedule_id = ?", SCHEDULE);
        ticker.advance(Duration.ofSeconds(1));

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/layout", SCHEDULE)
                        .header("If-None-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.data.configVersion").value(3))
                .andExpect(jsonPath("$.data.seats[0].basePrice").value(160000));
    }

    @Test
    @DisplayName("status는 Cache-Control no-store이고 ETag가 없다")
    void statusNoStore() throws Exception {
        seat(SCHEDULE, true);

        mockMvc.perform(get("/api/seats/schedules/{scheduleId}/status", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.data.seatCount").value(1))
                .andExpect(jsonPath("$.data.selectableBitmap").value("AQ=="));
    }

    @Test
    @DisplayName("좌석이 없는 회차는 layout·status도 404 SEAT_NOT_FOUND다")
    void layoutAndStatusNotFound() throws Exception {
        for (String path : List.of("/layout", "/status")) {
            mockMvc.perform(get("/api/seats/schedules/{scheduleId}" + path, 999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        }
    }
}
