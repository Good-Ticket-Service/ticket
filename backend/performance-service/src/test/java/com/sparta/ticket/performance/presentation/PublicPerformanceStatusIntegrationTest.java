package com.sparta.ticket.performance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/** 실제 MVC 요청 바인딩과 PostgreSQL 조회를 거쳐 이슈 #382의 판매 상태·정렬 정책을 확인한다. */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicPerformanceStatusIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("status 생략은 판매중·판매예정이며 검색에도 같은 기본값을 쓴다")
    void defaultsToOnSaleAndUpcoming() throws Exception {
        mockMvc.perform(get("/api/performances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0]", not(hasKey("status"))));
        mockMvc.perform(get("/api/performances").queryParam("keyword", "뮤지컬"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/performances").queryParam("keyword", "Epsilon"))
                .andExpect(jsonPath("$.data.content", empty()))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("세 상태의 7가지 조합은 항목·전체 개수·페이지에 같은 조건을 쓴다")
    void filtersAllStatusCombinations() throws Exception {
        String[] combinations = {
            "ON_SALE",
            "UPCOMING",
            "ENDED",
            "ON_SALE,UPCOMING",
            "ON_SALE,ENDED",
            "UPCOMING,ENDED",
            "ON_SALE,UPCOMING,ENDED"
        };
        Integer[][] ids = {{12}, {11, 10}, {14, 13}, {12, 11, 10}, {14, 13, 12}, {14, 13, 11, 10}, {14, 13, 12, 11, 10}
        };
        for (int i = 0; i < combinations.length; i++) {
            search(combinations[i])
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(ids[i])))
                    .andExpect(jsonPath("$.data.totalElements").value(ids[i].length));
            mockMvc.perform(get("/api/performances")
                            .queryParam("status", combinations[i])
                            .queryParam("page", "1")
                            .queryParam("size", "1"))
                    .andExpect(jsonPath("$.data.totalElements").value(ids[i].length))
                    .andExpect(jsonPath("$.data.totalPages").value(ids[i].length));
        }
    }

    @Test
    @DisplayName("쉼표·반복·혼합·중복·전달 순서는 같은 항목·count·페이지를 만든다")
    void normalizesRawHttpParameters() throws Exception {
        String expected = null;
        for (String[] values : List.of(
                new String[] {"ON_SALE,UPCOMING"},
                new String[] {"UPCOMING", "ON_SALE"},
                new String[] {"ON_SALE,UPCOMING", "UPCOMING"},
                new String[] {"ON_SALE,ON_SALE", "UPCOMING,ON_SALE"})) {
            String body = mockMvc.perform(get("/api/performances")
                            .queryParam("status", values)
                            .queryParam("page", "1")
                            .queryParam("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (expected == null) {
                expected = body;
            } else {
                assertThat(body).isEqualTo(expected);
            }
        }
        String single = search("ON_SALE").andReturn().getResponse().getContentAsString();
        String duplicated = search("ON_SALE,ON_SALE").andReturn().getResponse().getContentAsString();
        assertThat(duplicated).isEqualTo(single);
    }

    @Test
    @DisplayName("명시적 빈값·공백·빈 항목·알 수 없는 상태는 HTTP 400 VALIDATION_ERROR다")
    void rejectsInvalidRawHttpParameters() throws Exception {
        for (String[] values : List.of(
                new String[] {""},
                new String[] {","},
                new String[] {"ON_SALE,"},
                new String[] {",ON_SALE"},
                new String[] {"ON_SALE,,UPCOMING"},
                new String[] {" "},
                new String[] {"\u00a0"},
                new String[] {"ON_SALE", ""},
                new String[] {"ON_SALE,UPCOMING", " "},
                new String[] {"ON_SALE", "ENDED,"},
                new String[] {"on_sale"},
                new String[] {"ALL"})) {
            mockMvc.perform(get("/api/performances").queryParam("status", values))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.path").value("/api/performances"));
        }
        mockMvc.perform(get("/api/performances?status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("예매 오픈 전·정각·직후는 UPCOMING에서 ON_SALE로 바뀌며 count도 같다")
    void changesAtBookingOpen() throws Exception {
        LocalDateTime open = LocalDateTime.of(2026, 11, 10, 14, 0);
        clock.set(open.minusNanos(1_000_000));
        assertAlphaStatus("UPCOMING", true);
        assertAlphaStatus("ON_SALE", false);
        for (LocalDateTime now : List.of(open, open.plusNanos(1_000_000))) {
            clock.set(now);
            assertAlphaStatus("ON_SALE", true);
            assertAlphaStatus("UPCOMING", false);
        }
    }

    @Test
    @DisplayName("마지막 회차 시작 전·정각·직후에는 ENDED가 오픈 여부보다 우선한다")
    void endsExactlyAtLastStart() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 12, 2, 19, 0);
        clock.set(start.minusNanos(1_000_000));
        assertAlphaStatus("ON_SALE", true);
        assertAlphaStatus("ENDED", false);
        for (LocalDateTime now : List.of(start, start.plusNanos(1_000_000))) {
            clock.set(now);
            assertAlphaStatus("ON_SALE", false);
            assertAlphaStatus("UPCOMING", false);
            assertAlphaStatus("ENDED", true);
        }
    }

    @Test
    @DisplayName("이미 오픈한 회차가 시작돼도 남은 회차가 있으면 ON_SALE을 유지한다")
    void includesOpenedPastSchedulesForClassification() throws Exception {
        preparePastAndFutureAlpha();
        for (LocalDateTime now : List.of(NOW.minusMinutes(1), NOW, NOW.plusMinutes(1))) {
            clock.set(now);
            assertAlphaStatus("ON_SALE", true);
            assertAlphaStatus("UPCOMING", false);
        }
        // 남은 12-02 회차는 11-10 오픈 전이어도 11-01에 시작한 활성 회차의 오픈을 함께 판단한다.
        mockMvc.perform(get("/api/performances").queryParam("keyword", "Alpha"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("날짜 필터와 status는 서로 다른 활성 회차가 만족해도 되고 period는 전체 기간이다")
    void dateAndStatusAreIndependent() throws Exception {
        preparePastAndFutureAlpha();
        clock.set(NOW.plusMinutes(1));
        mockMvc.perform(get("/api/performances")
                        .queryParam("status", "ON_SALE")
                        .queryParam("keyword", "Alpha")
                        .queryParam("from", "2026-11-01")
                        .queryParam("to", "2026-11-01"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].period.from").value("2026-11-01"))
                .andExpect(jsonPath("$.data.content[0].period.to").value("2026-12-02"));
    }

    @Test
    @DisplayName("예매 마감·판매 중지·PENDING·FAILED 회차도 활성 회차이면 판매중 분류에 포함한다")
    void includesClosedSuspendedAndUnpreparedSchedules() throws Exception {
        jdbc.update("UPDATE p_schedules SET sales_suspended = true, seat_setup_status = 'PENDING' WHERE id = 1");
        jdbc.update("UPDATE p_schedules SET sales_suspended = true, seat_setup_status = 'FAILED' WHERE id = 2");
        clock.set(LocalDateTime.of(2026, 11, 11, 12, 0));
        assertAlphaStatus("ON_SALE", true);
        clock.set(LocalDateTime.of(2026, 12, 2, 18, 30));
        assertAlphaStatus("ON_SALE", true);
        mockMvc.perform(get("/api/performances").queryParam("sort", "BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)));
    }

    @Test
    @Sql(
            value = {
                "/sql/truncate.sql",
                "/sql/public-performance-fixture.sql",
                "/sql/public-performance-extra-fixture.sql"
            },
            config = @SqlConfig(encoding = "UTF-8"))
    @DisplayName("활성 회차 없음·삭제·취소 회차만 있음은 ENDED이며 전체 상태도 공개 조건은 유지한다")
    void treatsInactiveOnlySchedulesAsEnded() throws Exception {
        search("ENDED")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 16, 15)))
                .andExpect(jsonPath("$.data.totalElements").value(4));
        search("ON_SALE,UPCOMING,ENDED")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 12, 11, 10, 16, 15, 17)))
                .andExpect(jsonPath("$.data.totalElements").value(8));
        mockMvc.perform(get("/api/performances").queryParam("sort", "BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 17, 11, 10)));
    }

    @Test
    @DisplayName("중간 회차 시작 시 정렬 키가 바뀌고 판매예정이 판매중보다 먼저 올 수 있다")
    void remainingSchedulesDetermineSortWithoutStatusPriority() throws Exception {
        preparePastAndFutureAlpha();
        clock.set(NOW.minusMinutes(1));
        mockMvc.perform(get("/api/performances").queryParam("sort", "BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 10, 11)));
        clock.set(NOW);
        mockMvc.perform(get("/api/performances").queryParam("sort", "BOOKING_OPEN_ASC"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)));
        assertAlphaStatus("ON_SALE", true);
        search("UPCOMING").andExpect(jsonPath("$.data.content[*].id").value(contains(11)));
    }

    @Test
    @DisplayName("7가지 상태와 과거 날짜 필터를 조합해도 항목과 count가 함께 적용된다")
    void combinesEveryStatusWithDateFilter() throws Exception {
        for (String statuses : List.of(
                "ON_SALE",
                "UPCOMING",
                "ENDED",
                "ON_SALE,UPCOMING",
                "ON_SALE,ENDED",
                "UPCOMING,ENDED",
                "ON_SALE,UPCOMING,ENDED")) {
            ResultActions result = mockMvc.perform(get("/api/performances")
                    .queryParam("status", statuses)
                    .queryParam("from", "2026-10-30")
                    .queryParam("to", "2026-10-30"));
            if (statuses.contains("ENDED")) {
                result.andExpect(jsonPath("$.data.content[*].id").value(contains(14)))
                        .andExpect(jsonPath("$.data.totalElements").value(1));
            } else {
                result.andExpect(jsonPath("$.data.content", empty()))
                        .andExpect(jsonPath("$.data.totalElements").value(0));
            }
        }
    }

    @Test
    @Sql(
            value = {
                "/sql/truncate.sql",
                "/sql/public-performance-fixture.sql",
                "/sql/public-performance-many-fixture.sql"
            },
            config = @SqlConfig(encoding = "UTF-8"))
    @DisplayName("기본 상태의 최신순·오픈순 페이지에서 항목 누락 없이 같은 전체 개수를 유지한다")
    void paginatesDefaultStatuses() throws Exception {
        Integer[][] latest = {{12, 11, 10, 112, 111, 110, 109}, {108, 107, 106, 105, 104, 103, 102}, {101}};
        Integer[][] booking = {{12, 107, 101, 108, 102, 109, 103}, {110, 104, 111, 105, 11, 112, 106}, {10}};
        for (int page = 0; page < latest.length; page++) {
            for (String sort : List.of("LATEST", "BOOKING_OPEN_ASC")) {
                mockMvc.perform(get("/api/performances")
                                .queryParam("sort", sort)
                                .queryParam("page", Integer.toString(page))
                                .queryParam("size", "7"))
                        .andExpect(jsonPath("$.data.content[*].id")
                                .value(contains(sort.equals("LATEST") ? latest[page] : booking[page])))
                        .andExpect(jsonPath("$.data.totalElements").value(15))
                        .andExpect(jsonPath("$.data.totalPages").value(3));
            }
        }
    }

    @Test
    @DisplayName("조회 중 시계가 바뀌어도 한 요청의 필터·정렬·count는 서비스가 읽은 같은 시각을 쓴다")
    void usesOneRequestTime() throws Exception {
        doAnswer(invocation -> {
                    assertThat(invocation.getArgument(1, LocalDateTime.class)).isEqualTo(NOW);
                    clock.set(LocalDateTime.of(2027, 2, 1, 0, 0));
                    return invocation.callRealMethod();
                })
                .when(publicPerformanceReader)
                .search(any(), any());
        try {
            mockMvc.perform(get("/api/performances").queryParam("sort", "BOOKING_OPEN_ASC"))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
        } finally {
            reset(publicPerformanceReader);
        }
    }

    private ResultActions search(String statuses) throws Exception {
        return mockMvc.perform(get("/api/performances").queryParam("status", statuses));
    }

    private void assertAlphaStatus(String saleStatus, boolean included) throws Exception {
        ResultActions result = mockMvc.perform(
                get("/api/performances").queryParam("keyword", "Alpha").queryParam("status", saleStatus));
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(included ? 1 : 0));
        if (included) {
            result.andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        } else {
            result.andExpect(jsonPath("$.data.content", empty()));
        }
    }

    private void preparePastAndFutureAlpha() {
        jdbc.update("""
                UPDATE p_schedules SET start_at = '2026-11-01 12:00:00',
                    booking_open_at = '2026-10-30 10:00:00', booking_close_at = '2026-11-01 11:00:00'
                WHERE id = 1
                """);
    }
}
