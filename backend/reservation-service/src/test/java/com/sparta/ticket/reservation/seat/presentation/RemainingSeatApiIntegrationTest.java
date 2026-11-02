package com.sparta.ticket.reservation.seat.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 등급별 잔여석 조회(GET /api/seats/schedules/{scheduleId}/remaining, #241)를 실제 DB로 확인한다.
 * 좌석 상태(HOLD·SOLD)와 논리 삭제는 SQL로 직접 만든다.
 *
 * Note: 잔여석 캐시는 공용 컨텍스트 하나에서 함께 쓴다. 테스트마다 다른 회차를 써서 앞 테스트의 캐시가 섞이지 않게 한다.
 */
class RemainingSeatApiIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);
    private static final long VIP = 7L;
    private static final long R = 8L;
    private static final long S = 9L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MutableTicker ticker;

    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(NOW);
    }

    /** 좌석 하나를 만든다. 판매 제외 좌석도 가격은 둔다(seed와 같은 방식). */
    private Long seat(long scheduleId, long gradeId, String gradeName, boolean sellable) {
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        scheduleId,
                        venueSeatId,
                        gradeId,
                        gradeName,
                        100_000,
                        "A",
                        "1",
                        (int) venueSeatId,
                        sellable,
                        1L))
                .getId();
    }

    private void hold(Long seatId, LocalDateTime expiresAt) {
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = 77, hold_expires_at = ? WHERE id = ?",
                expiresAt,
                seatId);
    }

    private void sold(Long seatId) {
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = 77, hold_expires_at = NULL WHERE id = ?",
                seatId);
    }

    private ResultActions remaining(long scheduleId) throws Exception {
        // 회원 헤더 없이 부른다. 공개 API다
        return mockMvc.perform(get("/api/seats/schedules/{scheduleId}/remaining", scheduleId));
    }

    @Test
    @DisplayName("판매 대상 좌석 중 AVAILABLE만 센다: 만료 전 HOLD·만료됐지만 정리 전 HOLD·SOLD·판매 제외·삭제 좌석은 빠진다")
    void countsOnlySellableAvailable() throws Exception {
        long schedule = 1201L;
        seat(schedule, VIP, "VIP", true);
        seat(schedule, VIP, "VIP", true);
        hold(seat(schedule, VIP, "VIP", true), NOW.plusMinutes(5));
        hold(seat(schedule, VIP, "VIP", true), NOW.minusMinutes(1));
        sold(seat(schedule, VIP, "VIP", true));
        seat(schedule, VIP, "VIP", false);
        Long deleted = seat(schedule, VIP, "VIP", true);
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET deleted_at = now(), deleted_by = 'TEST' WHERE id = ?", deleted);

        remaining(schedule)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(schedule))
                .andExpect(jsonPath("$.data.grades[0].venueSeatGradeId").value(VIP))
                .andExpect(jsonPath("$.data.grades[0].gradeName").value("VIP"))
                .andExpect(jsonPath("$.data.grades[0].remaining").value(2));
    }

    @Test
    @DisplayName("판매 대상 좌석이 없는 등급은 빠지고, 모두 선점·판매된 등급은 0이며, 등급 id 오름차순이다")
    void gradeRules() throws Exception {
        long schedule = 1202L;
        // 등급 id 순서와 다르게 만든다
        seat(schedule, S, "S", false);
        sold(seat(schedule, R, "R", true));
        hold(seat(schedule, R, "R", true), NOW.plusMinutes(5));
        seat(schedule, VIP, "VIP", true);

        remaining(schedule)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grades[*].venueSeatGradeId").value(contains((int) VIP, (int) R)))
                .andExpect(jsonPath("$.data.grades[*].remaining").value(contains(1, 0)));
    }

    @Test
    @DisplayName("좌석이 없는 회차는 오류가 아니라 200과 빈 목록이다")
    void emptySchedule() throws Exception {
        remaining(1203L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(1203))
                .andExpect(jsonPath("$.data.grades", empty()))
                .andExpect(jsonPath("$.data.cached").value(false));
    }

    @Test
    @DisplayName("TTL 안의 두 번째 요청은 캐시 값(cached=true, 같은 asOf)이고, TTL이 지나면 다시 집계한다")
    void cacheAndExpiry() throws Exception {
        long schedule = 1204L;
        Long seatA = seat(schedule, VIP, "VIP", true);
        seat(schedule, VIP, "VIP", true);

        remaining(schedule)
                .andExpect(jsonPath("$.data.grades[0].remaining").value(2))
                .andExpect(jsonPath("$.data.cached").value(false))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-02T12:00:00+09:00"));

        // 좌석이 팔려도 TTL 안에는 캐시 값 그대로다
        sold(seatA);
        clock.set(NOW.plusSeconds(1));
        ticker.advance(Duration.ofSeconds(1));
        remaining(schedule)
                .andExpect(jsonPath("$.data.grades[0].remaining").value(2))
                .andExpect(jsonPath("$.data.cached").value(true))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-02T12:00:00+09:00"));

        // TTL(3초)이 지나면 다시 센다
        clock.set(NOW.plusSeconds(3));
        ticker.advance(Duration.ofSeconds(2));
        remaining(schedule)
                .andExpect(jsonPath("$.data.grades[0].remaining").value(1))
                .andExpect(jsonPath("$.data.cached").value(false))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-02T12:00:03+09:00"));
    }
}
