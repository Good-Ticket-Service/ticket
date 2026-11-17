package com.sparta.ticket.performance.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 이슈 #471: 검색어가 없고 판매 상태가 판매중·판매예정인 기본 목록의 예매 오픈 순 항목과 전체 개수가
 * 회차 조건(여러 미래 회차, 취소·삭제 회차, 시작 시각 경계)과 필터에서 의미대로 나오는지 확인한다.
 * 별도 SQL 경로는 진단에서 이득이 없어 되돌렸지만, 인덱스·비용 설정으로 실행 계획이 바뀌어도 결과가 같아야 하므로 남긴다.
 * 상태 조합·시각 경계·페이지 이동은 PublicPerformanceStatusIntegrationTest가 같은 API로 검증한다.
 *
 * <p>픽스처 기준 시각은 2026-11-01 12:00. 기본 목록은 공연 12·11·10이다.
 * 예매 오픈(시작 전 활성 회차의 최소값): 12 = 10-25, 11 = 11-05, 10 = 11-10.
 * 공연 10은 시작 전 활성 회차가 2개(회차 1·2)이고, 취소(3)·삭제(4) 회차도 있다.
 * 공연장 1(SEOUL): 10·12·13, 공연장 2(BUSAN): 11·14. 장르: 10·14 MUSICAL, 11 CONCERT, 12 PLAY.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicPerformanceDefaultListIntegrationTest extends PerformanceIntegrationTest {

    private static final List<String> SORTS = List.of("LATEST", "BOOKING_OPEN_ASC");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("미래 회차가 여러 개인 공연도 예매 오픈 순 항목과 전체 개수에 한 번만 들어간다")
    void countsPerformanceOnceWithManyFutureSchedules() throws Exception {
        // 공연 11에 시작 전 활성 회차를 둘 더한다. 하나는 오픈이 가장 이르다(10-20).
        insertSchedule(201, 11, "2026-12-21 19:00:00", "2026-10-20 14:00:00", "ACTIVE");
        insertSchedule(202, 11, "2026-12-22 19:00:00", "2026-11-20 14:00:00", "ACTIVE");
        for (MockHttpServletRequestBuilder request :
                List.of(list("BOOKING_OPEN_ASC"), list("BOOKING_OPEN_ASC").queryParam("status", "UPCOMING,ON_SALE"))) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(11, 12, 10)))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
        }
        mockMvc.perform(list("LATEST"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        // 페이지를 나눠도 공연이 중복되거나 빠지지 않는다.
        mockMvc.perform(list("BOOKING_OPEN_ASC").queryParam("size", "2").queryParam("page", "1"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    @DisplayName("취소 회차의 이른 오픈 시각은 예매 오픈 순에 쓰이지 않는다")
    void ignoresCanceledScheduleForBookingOrder() throws Exception {
        // 공연 10의 취소 회차 3은 오픈이 10-02로 가장 이르지만 정렬에 쓰이면 안 된다(픽스처 그대로).
        mockMvc.perform(list("BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("취소 공연·삭제 공연·초안·삭제 공연장의 미래 회차는 기본 목록 항목과 개수에 들어가지 않는다")
    void excludesNonListablePerformancesInCount() throws Exception {
        // 공연 20(초안)·21(삭제)·23(삭제 공연장)은 픽스처에 미래 회차가 있다. 취소 공연 22에도 하나 더한다.
        insertSchedule(203, 22, "2026-12-10 19:00:00", "2026-10-03 14:00:00", "ACTIVE");
        for (String sort : SORTS) {
            mockMvc.perform(list(sort))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
        }
    }

    @Test
    @DisplayName("기본 목록에 장르·지역 필터를 함께 쓰면 항목과 개수가 같은 조건을 따른다")
    void appliesGenreAndRegionFilters() throws Exception {
        for (String sort : SORTS) {
            // 공연 14도 MUSICAL·BUSAN이지만 판매종료라 기본 목록에서 빠진다.
            mockMvc.perform(list(sort).queryParam("genre", "MUSICAL"))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            mockMvc.perform(list(sort).queryParam("region", "BUSAN"))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(11)))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            mockMvc.perform(list(sort).queryParam("genre", "PLAY").queryParam("region", "BUSAN"))
                    .andExpect(jsonPath("$.data.content", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Test
    @DisplayName("날짜 필터는 남은 회차와 다른 회차가 만족해도 되고, 취소 회차로는 만족하지 않는다")
    void keepsDateFilterIndependentOfRemainingSchedule() throws Exception {
        // 공연 12에 이미 시작한 활성 회차를 더한다. 남은 회차(회차 6)는 그대로 있다.
        insertSchedule(204, 12, "2026-10-20 19:00:00", "2026-10-01 14:00:00", "ACTIVE");
        for (String sort : SORTS) {
            mockMvc.perform(list(sort).queryParam("from", "2026-10-20").queryParam("to", "2026-10-20"))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12)))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            // 공연 10의 회차 2(12-02)
            mockMvc.perform(list(sort).queryParam("from", "2026-12-02").queryParam("to", "2026-12-02"))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            // 11-25에는 공연 10의 취소 회차 3만 있다.
            mockMvc.perform(list(sort).queryParam("from", "2026-11-25").queryParam("to", "2026-11-25"))
                    .andExpect(jsonPath("$.data.content", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Test
    @DisplayName("시작 시각이 기준 시각과 같은 회차는 남은 회차가 아니다")
    void excludesScheduleStartingExactlyNow() throws Exception {
        // 공연 13(판매종료)에 기준 시각 정각에 시작하는 회차를 더해도 기본 목록에 들어오지 않는다.
        insertSchedule(205, 13, "2026-11-01 12:00:00", "2026-10-01 14:00:00", "ACTIVE");
        for (String sort : SORTS) {
            mockMvc.perform(list(sort))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
        }
        // 1초 뒤에 시작하면 남은 회차다. 오픈이 가장 일러 예매 오픈 순 맨 앞이다.
        jdbc.update("UPDATE p_schedules SET start_at = '2026-11-01 12:00:01', booking_close_at = '2026-11-01 12:00:01'"
                + " WHERE id = 205");
        mockMvc.perform(list("BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(13, 12, 11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    private MockHttpServletRequestBuilder list(String sort) {
        return get("/api/performances").queryParam("sort", sort);
    }

    private void insertSchedule(long id, long performanceId, String startAt, String bookingOpenAt, String status) {
        jdbc.update("""
                INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                                         seat_setup_attempts, version, created_at, created_by, updated_at, updated_by,
                                         deleted_at, deleted_by)
                VALUES (?, ?, ?::timestamp, ?::timestamp, ?::timestamp, ?, 'READY', 1, ?, FALSE, 1, 0,
                        '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL)
                """, id, performanceId, startAt, bookingOpenAt, startAt, status, "req-" + id);
    }
}
