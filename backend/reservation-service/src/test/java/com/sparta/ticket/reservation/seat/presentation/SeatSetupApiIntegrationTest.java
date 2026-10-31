package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupCommand;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupService;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSpec;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 회차 좌석 준비 내부 API(POST /internal/schedules/{scheduleId}/seat-setups, #190)를 실제 DB로 확인한다.
 * 계약은 docs/api.md 「좌석 내부 API」(#175)다. 좌석 상태(HOLD·SOLD)처럼 이 API로 만들 수 없는 상태는 SQL로 직접 만든다.
 */
class SeatSetupApiIntegrationTest extends ReservationIntegrationTest {

    private static final long SCHEDULE = 1001L;
    private static final long OTHER_SCHEDULE = 1002L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SeatSetupService seatSetupService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
    }

    /** 공연장 좌석 하나의 요청 줄. 기본은 판매 대상 VIP 150,000원이고, 바꿀 값만 덮어쓴다. */
    private static Map<String, Object> line(long venueSeatId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("venueSeatId", venueSeatId);
        line.put("venueSeatGradeId", 7);
        line.put("gradeName", "VIP");
        line.put("basePrice", 150_000);
        line.put("isSellable", true);
        line.put("section", "A");
        line.put("rowLabel", "1");
        line.put("seatNumber", (int) venueSeatId);
        return line;
    }

    private static List<Map<String, Object>> lines(long... venueSeatIds) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (long id : venueSeatIds) {
            lines.add(line(id));
        }
        return lines;
    }

    private static Map<String, Object> body(long scheduleId, long configVersion, List<Map<String, Object>> seats) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-" + configVersion);
        body.put("scheduleId", scheduleId);
        body.put("configVersion", configVersion);
        body.put("seats", seats);
        return body;
    }

    private ResultActions setup(long pathScheduleId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/internal/schedules/{scheduleId}/seat-setups", pathScheduleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private ResultActions setup(long configVersion, long... venueSeatIds) throws Exception {
        return setup(SCHEDULE, body(SCHEDULE, configVersion, lines(venueSeatIds)));
    }

    /** 회차 좌석(삭제 포함)을 공연장 좌석 ID 순서로 읽는다. */
    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("""
                SELECT id, venue_seat_id, base_price, is_sellable, status, config_version, deleted_at
                  FROM p_schedule_seats
                 WHERE schedule_id = ?
                 ORDER BY venue_seat_id
                """, SCHEDULE);
    }

    private Map<String, Object> row(long venueSeatId) {
        return jdbcTemplate.queryForMap(
                "SELECT id, base_price, status, config_version, deleted_at FROM p_schedule_seats"
                        + " WHERE schedule_id = ? AND venue_seat_id = ?",
                SCHEDULE,
                venueSeatId);
    }

    private int activeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_schedule_seats WHERE schedule_id = ? AND deleted_at IS NULL",
                Integer.class,
                SCHEDULE);
    }

    @Nested
    @DisplayName("처음 준비와 같은 버전 재요청")
    class Create {

        @Test
        @DisplayName("처음 준비하면 좌석을 모두 AVAILABLE로 만들고, ApiResponse 없이 requestId·좌석 수·판매 대상 수를 준다")
        void createsSeats() throws Exception {
            List<Map<String, Object>> seats = lines(1, 2, 3);
            seats.get(2).put("isSellable", false);
            seats.get(2).put("basePrice", null);

            setup(SCHEDULE, body(SCHEDULE, 1, seats))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(hasKey("data"))))
                    .andExpect(jsonPath("$.requestId").value("req-1"))
                    .andExpect(jsonPath("$.scheduleId").value(SCHEDULE))
                    .andExpect(jsonPath("$.configVersion").value(1))
                    .andExpect(jsonPath("$.seatCount").value(3))
                    .andExpect(jsonPath("$.sellableCount").value(2));

            assertThat(rows())
                    .extracting(r -> r.get("status"), r -> ((Number) r.get("config_version")).longValue())
                    .containsOnly(tuple("AVAILABLE", 1L));
            assertThat(row(3)).containsEntry("base_price", null).containsEntry("deleted_at", null);
        }

        @Test
        @DisplayName("같은 구성 버전을 다시 보내면 좌석을 바꾸지 않고 같은 결과를 주며, 응답 requestId는 이번 요청 값이다")
        void sameVersionReturnsSavedResult() throws Exception {
            setup(1, 1, 2).andExpect(status().isOk());
            List<Map<String, Object>> before = rows();

            Map<String, Object> retry = body(SCHEDULE, 1, lines(1, 2));
            retry.put("requestId", "req-retry");
            setup(SCHEDULE, retry)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("req-retry"))
                    .andExpect(jsonPath("$.configVersion").value(1))
                    .andExpect(jsonPath("$.seatCount").value(2))
                    .andExpect(jsonPath("$.sellableCount").value(2));

            assertThat(rows()).isEqualTo(before);
        }

        @Test
        @DisplayName("다른 회차의 좌석은 건드리지 않는다")
        void otherScheduleUntouched() throws Exception {
            setup(OTHER_SCHEDULE, body(OTHER_SCHEDULE, 5, lines(1, 2))).andExpect(status().isOk());

            setup(1, 1, 2, 3)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.seatCount").value(3));

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM p_schedule_seats WHERE schedule_id = ? AND config_version = 5",
                            Integer.class,
                            OTHER_SCHEDULE))
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("버전이 다른 요청")
    class Versions {

        @Test
        @DisplayName("낮은 구성 버전은 저장하지 않고 409 SEAT_SETUP_STALE_VERSION이다")
        void lowerVersionIsStale() throws Exception {
            setup(2, 1, 2).andExpect(status().isOk());
            List<Map<String, Object>> before = rows();

            setup(1, 1, 2, 3)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SEAT_SETUP_STALE_VERSION"));

            assertThat(rows()).isEqualTo(before);
        }

        @Test
        @DisplayName("높은 구성 버전은 공연장 좌석 ID로 기존 행을 갱신하고(좌석 id 유지), 빠진 좌석은 논리 삭제, 새 좌석은 추가한다")
        void higherVersionReplacesInPlace() throws Exception {
            setup(1, 1, 2, 3).andExpect(status().isOk());
            long seat2 = ((Number) row(2).get("id")).longValue();

            List<Map<String, Object>> v2 = lines(2, 3, 4);
            v2.get(0).put("basePrice", 99_000);
            setup(SCHEDULE, body(SCHEDULE, 2, v2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configVersion").value(2))
                    .andExpect(jsonPath("$.seatCount").value(3));

            assertThat(row(1).get("deleted_at")).isNotNull();
            // 논리 삭제한 행에도 새 버전을 적어, 삭제된 행까지 포함해 반영된 버전을 알 수 있다
            assertThat(((Number) row(1).get("config_version")).longValue()).isEqualTo(2L);
            assertThat(((Number) row(2).get("id")).longValue()).isEqualTo(seat2);
            assertThat(row(2)).containsEntry("base_price", 99_000).containsEntry("deleted_at", null);
            assertThat(((Number) row(2).get("config_version")).longValue()).isEqualTo(2L);
            assertThat(row(4)).containsEntry("status", "AVAILABLE").containsEntry("deleted_at", null);
            assertThat(activeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빠졌던 좌석이 다음 구성에 다시 들어오면 논리 삭제된 행을 되살린다(UK 위반 없음, 좌석 id 유지)")
        void restoresDeletedSeat() throws Exception {
            setup(1, 1, 2).andExpect(status().isOk());
            long seat1 = ((Number) row(1).get("id")).longValue();
            setup(2, 2).andExpect(status().isOk());

            setup(3, 1, 2)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.seatCount").value(2));

            assertThat(((Number) row(1).get("id")).longValue()).isEqualTo(seat1);
            assertThat(row(1)).containsEntry("deleted_at", null).containsEntry("status", "AVAILABLE");
            assertThat(((Number) row(1).get("config_version")).longValue()).isEqualTo(3L);
            assertThat(rows()).hasSize(2);
        }

        @Test
        @DisplayName("HOLD·SOLD·만료됐지만 정리 안 된 HOLD가 하나라도 있으면 바꾸지 않고 409 SEAT_SETUP_IN_USE다")
        void inUseSeatsBlockReplacement() throws Exception {
            for (String state : List.of("HOLD", "SOLD", "EXPIRED_HOLD")) {
                clean();
                setup(1, 1, 2).andExpect(status().isOk());
                switch (state) {
                    case "HOLD" -> occupy(1, "HOLD", LocalDateTime.now().plusMinutes(10));
                    case "SOLD" -> occupy(1, "SOLD", null);
                    default -> occupy(1, "HOLD", LocalDateTime.now().minusMinutes(1));
                }
                List<Map<String, Object>> before = rows();

                setup(2, 1, 2, 3)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("SEAT_SETUP_IN_USE"));

                assertThat(rows()).as(state).isEqualTo(before);
            }
        }

        private void occupy(long venueSeatId, String status, LocalDateTime holdExpiresAt) {
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = ?, reservation_id = 77, hold_expires_at = ?"
                            + " WHERE schedule_id = ? AND venue_seat_id = ?",
                    status,
                    holdExpiresAt,
                    SCHEDULE,
                    venueSeatId);
        }
    }

    @Nested
    @DisplayName("400 VALIDATION_ERROR")
    class Validation {

        private void rejected(Map<String, Object> body) throws Exception {
            rejected(SCHEDULE, body);
        }

        private void rejected(long pathScheduleId, Map<String, Object> body) throws Exception {
            setup(pathScheduleId, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            assertThat(rows()).isEmpty();
        }

        private Map<String, Object> withLine(UnaryOperator<Map<String, Object>> change) {
            return body(SCHEDULE, 1, List.of(change.apply(line(1))));
        }

        @Test
        @DisplayName("seats가 비었거나, venueSeatId가 겹치거나, 경로와 본문의 scheduleId가 다르면 400이다")
        void requestLevel() throws Exception {
            rejected(body(SCHEDULE, 1, List.of()));
            rejected(body(SCHEDULE, 1, lines(1, 1)));
            rejected(OTHER_SCHEDULE, body(SCHEDULE, 1, lines(1)));
            Map<String, Object> noRequestId = body(SCHEDULE, 1, lines(1));
            noRequestId.remove("requestId");
            rejected(noRequestId);
            rejected(body(SCHEDULE, 0, lines(1)));
        }

        @Test
        @DisplayName("판매 대상인데 가격이 없거나, 가격이 0 이하거나, 좌석 번호가 1보다 작으면 400이다")
        void priceAndNumber() throws Exception {
            rejected(withLine(l -> {
                l.put("basePrice", null);
                return l;
            }));
            rejected(withLine(l -> {
                l.put("basePrice", 0);
                return l;
            }));
            rejected(withLine(l -> {
                l.put("seatNumber", 0);
                return l;
            }));
            rejected(withLine(l -> {
                l.remove("isSellable");
                return l;
            }));
        }

        @Test
        @DisplayName("section 20자, rowLabel 10자, gradeName 20자를 넘으면 400이다")
        void lengths() throws Exception {
            rejected(withLine(l -> {
                l.put("section", "S".repeat(21));
                return l;
            }));
            rejected(withLine(l -> {
                l.put("rowLabel", "R".repeat(11));
                return l;
            }));
            rejected(withLine(l -> {
                l.put("gradeName", "G".repeat(21));
                return l;
            }));
        }

        @Test
        @DisplayName("판매 제외 좌석은 가격이 없어도 된다")
        void excludedSeatWithoutPrice() throws Exception {
            setup(SCHEDULE, withLine(l -> {
                        l.put("isSellable", false);
                        l.put("basePrice", null);
                        return l;
                    }))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sellableCount").value(0));
        }
    }

    @Nested
    @DisplayName("실패 시나리오: 오류가 제대로 나고 기존 좌석이 그대로 남는다")
    class Failures {

        private ResultActions raw(String content) throws Exception {
            return mockMvc.perform(post("/internal/schedules/{scheduleId}/seat-setups", SCHEDULE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content));
        }

        private void badRequest(Map<String, Object> body) throws Exception {
            setup(SCHEDULE, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("필수 필드가 하나라도 빠지면 400이다: 본문의 scheduleId·configVersion·seats, 좌석의 7개 필수 필드")
        void missingRequiredFields() throws Exception {
            for (String field : List.of("scheduleId", "configVersion", "seats")) {
                Map<String, Object> body = body(SCHEDULE, 1, lines(1));
                body.remove(field);
                badRequest(body);
            }
            for (String field : List.of(
                    "venueSeatId",
                    "venueSeatGradeId",
                    "gradeName",
                    "isSellable",
                    "section",
                    "rowLabel",
                    "seatNumber")) {
                Map<String, Object> line = line(1);
                line.remove(field);
                badRequest(body(SCHEDULE, 1, List.of(line)));
            }
            // 빈 문자열 이름·위치와 null 좌석 줄도 거절한다
            for (String field : List.of("gradeName", "section", "rowLabel")) {
                Map<String, Object> line = line(1);
                line.put(field, " ");
                badRequest(body(SCHEDULE, 1, List.of(line)));
            }
            List<Map<String, Object>> withNull = new ArrayList<>(lines(1));
            withNull.add(null);
            badRequest(body(SCHEDULE, 1, withNull));
            assertThat(rows()).isEmpty();
        }

        @Test
        @DisplayName("타입이 틀리거나 JSON이 깨지면 400이고 좌석을 만들지 않는다")
        void malformedBody() throws Exception {
            raw("{\"requestId\":\"r\",\"scheduleId\":\"abc\",\"configVersion\":1,\"seats\":[]}")
                    .andExpect(status().isBadRequest());
            raw("{\"requestId\":\"r\",\"scheduleId\":" + SCHEDULE + ",\"configVersion\":1,\"seats\":[{")
                    .andExpect(status().isBadRequest());
            raw("").andExpect(status().isBadRequest());
            assertThat(rows()).isEmpty();
        }

        @Test
        @DisplayName("오류 응답은 공통 형식(status·code·message·path)이고 내부 정보(SQL·예외 클래스)를 담지 않는다")
        void errorResponseFormat() throws Exception {
            setup(2, 1, 2).andExpect(status().isOk());
            String body = setup(1, 1)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("SEAT_SETUP_STALE_VERSION"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").value("/internal/schedules/" + SCHEDULE + "/seat-setups"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body).doesNotContain("Exception", "SELECT", "p_schedule_seats");
        }

        @Test
        @DisplayName("교체 중 실패하면 기존 구성(v1)이 그대로 남는다(갱신·삭제까지 모두 되돌아감)")
        void failedReplacementKeepsPreviousSeats() throws Exception {
            setup(1, 1, 2, 3).andExpect(status().isOk());
            List<Map<String, Object>> before = rows();
            // 검증을 거치지 않고 새 좌석 4를 두 번 넣는다. 2·3 갱신과 1 삭제 뒤 INSERT에서 UK 위반이 난다
            ScheduleSeatSpec seat2 = new ScheduleSeatSpec(2L, 7L, "VIP", 99_000, "A", "1", 2, true);
            ScheduleSeatSpec seat4 = new ScheduleSeatSpec(4L, 7L, "VIP", 99_000, "A", "1", 4, true);

            assertThatThrownBy(() -> seatSetupService.setup(
                            new SeatSetupCommand("req-2", SCHEDULE, 2, List.of(seat2, seat4, seat4))))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(rows()).isEqualTo(before);
        }

        @Test
        @DisplayName("모든 좌석이 논리 삭제된 회차에도 낮은 버전은 409 SEAT_SETUP_STALE_VERSION이다(삭제된 행의 버전까지 본다)")
        void staleWhenAllSeatsDeleted() throws Exception {
            setup(2, 1, 2).andExpect(status().isOk());
            // 정상 경로로는 생기지 않는 상태: 회차 좌석이 모두 삭제됨
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET deleted_at = now(), deleted_by = 'TEST' WHERE schedule_id = ?",
                    SCHEDULE);

            setup(1, 1, 2)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SEAT_SETUP_STALE_VERSION"));
            assertThat(activeCount()).isZero();
        }

        @Test
        @DisplayName("논리 삭제된 행이 점유돼 있으면 되살리지 않고 409 SEAT_SETUP_IN_USE다(판 좌석의 예약 정보를 지우지 않는다)")
        void deletedButOccupiedRowBlocks() throws Exception {
            setup(1, 1, 2).andExpect(status().isOk());
            // 정상 경로로는 생기지 않는 상태: 삭제된 행이 SOLD
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = 'SOLD', reservation_id = 77, deleted_at = now(),"
                            + " deleted_by = 'TEST' WHERE schedule_id = ? AND venue_seat_id = 1",
                    SCHEDULE);

            setup(2, 1, 2)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SEAT_SETUP_IN_USE"));
            assertThat(row(1)).containsEntry("status", "SOLD");
            assertThat(((Number) row(1).get("config_version")).longValue()).isEqualTo(1L);
        }

        @Test
        @DisplayName("409로 거절된 뒤 막던 좌석이 풀리면, 같은 요청을 다시 보내 교체에 성공한다")
        void retryAfterInUseSucceeds() throws Exception {
            setup(1, 1, 2).andExpect(status().isOk());
            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = 77, hold_expires_at = now()"
                            + " WHERE schedule_id = ? AND venue_seat_id = 1",
                    SCHEDULE);
            setup(2, 1, 2).andExpect(status().isConflict());

            jdbcTemplate.update(
                    "UPDATE p_schedule_seats SET status = 'AVAILABLE', reservation_id = NULL, hold_expires_at = NULL"
                            + " WHERE schedule_id = ? AND venue_seat_id = 1",
                    SCHEDULE);

            setup(2, 1, 2)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configVersion").value(2));
            assertThat(((Number) row(1).get("config_version")).longValue()).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("저장 중 실패하면 좌석이 하나도 남지 않는다(검증을 거치지 않고 같은 공연장 좌석을 두 번 넣어 UK 위반을 낸다)")
    void failedSetupLeavesNothing() {
        ScheduleSeatSpec seat = new ScheduleSeatSpec(1L, 7L, "VIP", 150_000, "A", "1", 1, true);
        ScheduleSeatSpec other = new ScheduleSeatSpec(2L, 7L, "VIP", 150_000, "A", "1", 2, true);

        assertThatThrownBy(() ->
                        seatSetupService.setup(new SeatSetupCommand("req-1", SCHEDULE, 1, List.of(seat, other, seat))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rows()).isEmpty();
    }
}
