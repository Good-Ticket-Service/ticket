package com.sparta.ticket.performance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 공개 공연 목록·상세 API를 실제 PostgreSQL로 호출한다. 명세는 .local/performance/api.md 「공개 조회」다.
 * 데이터는 sql/public-performance-fixture.sql이며 기준 시각은 2026-11-01 12:00(KST)이다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicPerformanceApiIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ResultActions listAll(String query) throws Exception {
        // Note: 이 클래스의 기존 회귀 사례는 종료 공연도 포함한다. 기본 상태 정책은 StatusIntegrationTest에서 검증한다.
        return mockMvc.perform(get("/api/performances" + query).param("status", "ON_SALE,UPCOMING,ENDED"));
    }

    @Nested
    @DisplayName("GET /api/performances")
    class Search {

        @Test
        @DisplayName("전체 상태를 고르면 공개되고 삭제·취소되지 않은 공연만 최근 공개 순으로 나온다(초안·삭제·취소·삭제된 공연장 제외)")
        void listsOnlyPublishedActivePerformances() throws Exception {
            listAll("")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 12, 11, 10)))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.sort").value("LATEST"))
                    .andExpect(jsonPath("$.data", not(hasKey("items"))));
        }

        @Test
        @DisplayName("항목은 명세 필드만 담고, 기간은 삭제·취소 회차를 뺀 활성 회차의 첫날·마지막 날이다")
        void itemShape() throws Exception {
            listAll("?keyword=Alpha")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(10))
                    .andExpect(jsonPath("$.data.content[0].venue.id").value(1))
                    .andExpect(jsonPath("$.data.content[0].venue.name").value("서울 공연장"))
                    .andExpect(jsonPath("$.data.content[0].venue.address").value("서울특별시 중구 예시로 10"))
                    .andExpect(jsonPath("$.data.content[0].venue.region").value("서울"))
                    .andExpect(jsonPath("$.data.content[0].title").value("뮤지컬 Alpha"))
                    .andExpect(jsonPath("$.data.content[0].genre").value("MUSICAL"))
                    .andExpect(jsonPath("$.data.content[0].posterUrl").value("https://example.com/10.jpg"))
                    .andExpect(jsonPath("$.data.content[0].operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.content[0].period.from").value("2026-12-01"))
                    .andExpect(jsonPath("$.data.content[0].period.to").value("2026-12-02"))
                    .andExpect(jsonPath("$.data.content[0]", not(hasKey("sellerId"))))
                    .andExpect(jsonPath("$.data.content[0]", not(hasKey("version"))))
                    .andExpect(jsonPath("$.data.content[0]", not(hasKey("publicationStatus"))));
        }

        @Test
        @DisplayName("활성 회차가 없는 공연은 period가 null이고, 포스터가 없으면 posterUrl이 null이다")
        void nullPeriodAndPoster() throws Exception {
            listAll("?genre=SPORTS")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(13)))
                    .andExpect(jsonPath("$.data.content[0]", hasKey("period")))
                    .andExpect(jsonPath("$.data.content[0].period").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[0]", hasKey("posterUrl")))
                    .andExpect(jsonPath("$.data.content[0].posterUrl").value(nullValue()));
        }

        @Test
        @DisplayName("장르로 거른다")
        void filtersByGenre() throws Exception {
            listAll("?genre=MUSICAL")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 10)))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("공연장 지역으로 거른다")
        void filtersByRegion() throws Exception {
            listAll("?region=부산")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 11)))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("검색어는 공연명 부분 일치이며 대소문자와 앞뒤 공백을 무시한다")
        void filtersByKeyword() throws Exception {
            listAll("?keyword= gAmMa ")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12)));
            listAll("?keyword=뮤지컬").andExpect(jsonPath("$.data.content[*].id").value(contains(14, 10)));
        }

        @Test
        @DisplayName("검색어의 %와 _는 와일드카드가 아니라 글자로 찾는다")
        void keywordWildcardsAreLiteral() throws Exception {
            // Note: URL 템플릿에 %를 직접 쓰면 MockMvc가 한 번 더 인코딩하므로 param으로 넘긴다.
            mockMvc.perform(get("/api/performances").param("keyword", "%"))
                    .andExpect(jsonPath("$.data.content", empty()));
            mockMvc.perform(get("/api/performances").param("keyword", "_"))
                    .andExpect(jsonPath("$.data.content", empty()));
        }

        @Test
        @DisplayName("빈 검색어·지역은 필터를 적용하지 않는다")
        void blankFiltersAreIgnored() throws Exception {
            mockMvc.perform(get("/api/performances").param("keyword", "  ").param("region", ""))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
        }

        @Test
        @DisplayName("기간 필터는 시작일(KST)이 [from, to]에 드는 활성 회차가 있는 공연이다")
        void filtersByScheduleDate() throws Exception {
            listAll("?from=2026-12-01&to=2026-12-01")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
            listAll("?from=2026-12-02&to=2026-12-02")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
            listAll("?from=2026-12-15")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11)));
            listAll("?to=2026-10-31").andExpect(jsonPath("$.data.content[*].id").value(contains(14)));
        }

        @Test
        @DisplayName("취소·삭제된 회차의 날짜로는 걸리지 않는다")
        void dateFilterIgnoresInactiveSchedules() throws Exception {
            listAll("?from=2026-11-25&to=2026-11-25")
                    .andExpect(jsonPath("$.data.content", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.totalPages").value(0));
            listAll("?from=2026-12-31&to=2026-12-31").andExpect(jsonPath("$.data.content", empty()));
        }

        @Test
        @DisplayName("필터를 함께 쓰면 모두 만족하는 공연만 나온다")
        void combinesFilters() throws Exception {
            listAll("?genre=MUSICAL&region=서울&from=2026-12-01&to=2026-12-31")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC는 남은 활성 회차의 가장 이른 오픈 순이고, 그런 회차가 없는 공연은 마지막이다")
        void sortsByNextBookingOpen() throws Exception {
            // 10은 취소·삭제 회차의 오픈(10-02)이 가장 이르지만 활성 회차 기준(11-10)으로 정렬된다. 14(마감 지남)와 13(회차 없음)은 마지막이다.
            listAll("?sort=BOOKING_OPEN_ASC")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10, 14, 13)))
                    .andExpect(jsonPath("$.data.sort").value("BOOKING_OPEN_ASC"));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC는 마감됐어도 시작 전인 회차를 정렬에 포함한다")
        void bookingOpenSortUsesCurrentTime() throws Exception {
            // 10의 12-02 회차는 예매가 마감됐어도 19시 시작 전이라 정렬 키가 남는다.
            // 11(12-20 마감)과 12(2027-01-10 마감)는 아직 판매 중이다.
            clock.set(LocalDateTime.of(2026, 12, 2, 18, 30));
            listAll("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10, 14, 13)));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC에서 예매 마감 정각이어도 공연 시작 전이면 정렬 키가 남는다")
        void bookingCloseAtNowIsClosed() throws Exception {
            // 11은 예매 마감 정각이지만 공연 시작 전이므로 12 다음이다. 종료 묶음은 14, 13, 10이다.
            clock.set(LocalDateTime.of(2026, 12, 20, 18, 0));
            listAll("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 14, 13, 10)));
        }

        @Test
        @Sql(
                value = {
                    "/sql/truncate.sql",
                    "/sql/public-performance-fixture.sql",
                    "/sql/public-performance-extra-fixture.sql"
                },
                config = @SqlConfig(encoding = "UTF-8"))
        @DisplayName("삭제 회차만·취소 회차만 있는 공연도 목록과 건수에 들어가고 period는 null이며, 날짜 필터에는 걸리지 않는다")
        void performancesWithOnlyInactiveSchedules() throws Exception {
            listAll("")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 12, 11, 10, 16, 15, 17)))
                    .andExpect(jsonPath("$.data.totalElements").value(8));
            listAll("?genre=PLAY&region=서울")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 16, 15)))
                    .andExpect(jsonPath("$.data.content[1].period").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[2].period").value(nullValue()))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
            listAll("?from=2026-12-10&to=2026-12-11")
                    .andExpect(jsonPath("$.data.content", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
            mockMvc.perform(get("/api/performances/15"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.period").value(nullValue()));
        }

        @Test
        @Sql(
                value = {
                    "/sql/truncate.sql",
                    "/sql/public-performance-fixture.sql",
                    "/sql/public-performance-extra-fixture.sql"
                },
                config = @SqlConfig(encoding = "UTF-8"))
        @DisplayName("BOOKING_OPEN_ASC에서 오픈 시각이 같으면 ID 역순이다")
        void bookingOpenTieBreaksByIdDesc() throws Exception {
            listAll("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 17, 11, 10, 16, 15, 14, 13)));
        }

        @Test
        @DisplayName("page와 size로 나눠 주고 totalElements는 전체 건수다")
        void paginates() throws Exception {
            listAll("?page=1&size=2")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11)))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.size").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(3));
            listAll("?page=5&size=2")
                    .andExpect(jsonPath("$.data.content", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(3));
            listAll("?size=100").andExpect(status().isOk());
        }

        @Test
        @Sql(
                value = {
                    "/sql/truncate.sql",
                    "/sql/public-performance-fixture.sql",
                    "/sql/public-performance-many-fixture.sql"
                },
                config = @SqlConfig(encoding = "UTF-8"))
        @DisplayName("한 페이지를 넘는 최근 공개 순 목록은 기본 20개씩 나뉘고, 뒤 페이지의 기간도 활성 회차 전체 기준이다")
        void paginatesLatestOverOnePage() throws Exception {
            listAll("")
                    .andExpect(jsonPath("$.data.content[*].id")
                            .value(contains(
                                    14, 13, 12, 11, 10, 125, 124, 123, 122, 121, 120, 119, 118, 117, 116, 115, 114, 113,
                                    112, 111)))
                    .andExpect(jsonPath("$.data.totalElements").value(30));
            listAll("?page=1")
                    .andExpect(jsonPath("$.data.content[*].id")
                            .value(contains(110, 109, 108, 107, 106, 105, 104, 103, 102, 101)))
                    .andExpect(jsonPath("$.data.content[0].title").value("대량 공연 110"))
                    .andExpect(jsonPath("$.data.content[0].venue.name").value("서울 공연장"))
                    // 110은 지난 회차(10-15)와 판매 예정 회차(2027-01-05)가 함께 있다
                    .andExpect(jsonPath("$.data.content[0].period.from").value("2026-10-15"))
                    .andExpect(jsonPath("$.data.content[0].period.to").value("2027-01-05"))
                    .andExpect(jsonPath("$.data.totalElements").value(30));
        }

        @Test
        @Sql(
                value = {
                    "/sql/truncate.sql",
                    "/sql/public-performance-fixture.sql",
                    "/sql/public-performance-many-fixture.sql"
                },
                config = @SqlConfig(encoding = "UTF-8"))
        @DisplayName("BOOKING_OPEN_ASC는 페이지를 넘겨도 순서가 이어지고, 오픈할 회차가 없는 묶음과의 경계에서도 빠지거나 겹치지 않는다")
        void paginatesBookingOpenOverOnePage() throws Exception {
            listAll("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.content[*].id")
                            .value(contains(
                                    12, 107, 101, 108, 102, 109, 103, 110, 104, 111, 105, 11, 112, 106, 10, 125, 124,
                                    123, 122, 121)))
                    // 정렬은 판매 예정 회차(오픈 11-01)로 하지만 기간은 지난 회차까지 포함한다
                    .andExpect(jsonPath("$.data.content[2].id").value(101))
                    .andExpect(jsonPath("$.data.content[2].period.from").value("2026-10-15"))
                    .andExpect(jsonPath("$.data.content[2].period.to").value("2027-01-05"))
                    .andExpect(jsonPath("$.data.totalElements").value(30));
            listAll("?sort=BOOKING_OPEN_ASC&page=1")
                    .andExpect(jsonPath("$.data.content[*].id")
                            .value(contains(120, 119, 118, 117, 116, 115, 114, 113, 14, 13)))
                    .andExpect(jsonPath("$.data.content[7].period.from").value("2026-10-15"))
                    .andExpect(jsonPath("$.data.content[7].period.to").value("2026-10-15"))
                    .andExpect(jsonPath("$.data.content[9].period").value(nullValue()));
            listAll("?sort=BOOKING_OPEN_ASC&page=2")
                    .andExpect(jsonPath("$.data.content", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(30));
            // 7개씩이면 두 번째 페이지는 판매 예정 묶음 안, 세 번째 페이지는 두 묶음에 걸친다
            listAll("?sort=BOOKING_OPEN_ASC&page=1&size=7")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(110, 104, 111, 105, 11, 112, 106)));
            listAll("?sort=BOOKING_OPEN_ASC&page=2&size=7")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10, 125, 124, 123, 122, 121, 120)));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC의 기간은 마감된 회차까지 포함한 활성 회차 전체 기준이다")
        void bookingOpenPeriodIncludesClosedSchedules() throws Exception {
            // 12-01 18:30이면 10의 12-01 회차는 마감됐고 12-02 회차만 판매 중이다. 기간은 두 회차를 모두 본다.
            clock.set(LocalDateTime.of(2026, 12, 1, 18, 30));
            listAll("?sort=BOOKING_OPEN_ASC&keyword=Alpha")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                    .andExpect(jsonPath("$.data.content[0].period.from").value("2026-12-01"))
                    .andExpect(jsonPath("$.data.content[0].period.to").value("2026-12-02"));
        }

        @Test
        @DisplayName("목록의 쿼리들은 한 스냅숏을 읽어, 조회 도중 다른 연결이 커밋한 삭제가 그 응답에 섞이지 않는다")
        void searchReadsOneSnapshot() throws Exception {
            doAnswer(invocation -> {
                        // 서비스 트랜잭션의 첫 문장이다. REPEATABLE READ라면 여기서 스냅숏이 잡힌다.
                        assertThat(jdbc.queryForObject("select current_setting('transaction_isolation')", String.class))
                                .isEqualTo("repeatable read");
                        try (Connection other = dataSource.getConnection();
                                Statement statement = other.createStatement()) {
                            other.setAutoCommit(true);
                            statement.executeUpdate("UPDATE p_performances SET deleted_at = now() WHERE id = 10");
                        }
                        return invocation.callRealMethod();
                    })
                    .when(publicPerformanceReader)
                    .search(any(), any());

            // 삭제는 스냅숏 뒤에 커밋됐으므로 이 응답의 ID·기간·건수는 모두 삭제 전 시점이다
            listAll("")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 12, 11, 10)))
                    .andExpect(jsonPath("$.data.content[4].period.from").value("2026-12-01"))
                    .andExpect(jsonPath("$.data.totalElements").value(5));

            reset(publicPerformanceReader);
            listAll("")
                    .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 12, 11)))
                    .andExpect(jsonPath("$.data.totalElements").value(4));
        }

        @Test
        @DisplayName("from이 to보다 늦으면 400 INVALID_TIME_RANGE")
        void rejectsReversedDateRange() throws Exception {
            listAll("?from=2026-12-02&to=2026-12-01")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"))
                    .andExpect(jsonPath("$.path").value("/api/performances"));
        }

        @Test
        @DisplayName("page·size 범위, 알 수 없는 장르·정렬, 날짜 형식이 틀리면 400 VALIDATION_ERROR")
        void rejectsInvalidQuery() throws Exception {
            for (String query : new String[] {
                "?page=-1", "?size=0", "?size=101", "?genre=OPERA", "?sort=PRICE", "?from=2026-13-01", "?to=12/01"
            }) {
                listAll(query)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
    }

    @Nested
    @DisplayName("GET /api/performances/{performanceId}")
    class Detail {

        @Test
        @DisplayName("상세 성공은 기간을 포함한 기본 정보와 가격의 두 SELECT, 미존재는 한 SELECT만 실행한다")
        void executesTwoSelectsForDetail() throws Exception {
            Statistics statistics =
                    entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            boolean wasEnabled = statistics.isStatisticsEnabled();
            statistics.setStatisticsEnabled(true);
            try {
                for (long id : new long[] {10, 13, 14, 22}) {
                    statistics.clear();
                    mockMvc.perform(get("/api/performances/" + id)).andExpect(status().isOk());
                    assertThat(statistics.getPrepareStatementCount())
                            .as("공연 %s", id)
                            .isEqualTo(2);
                }
                statistics.clear();
                mockMvc.perform(get("/api/performances/999")).andExpect(status().isNotFound());
                assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            } finally {
                statistics.setStatisticsEnabled(wasEnabled);
                statistics.clear();
            }
        }

        @Test
        @DisplayName("기간은 예매가 끝난 과거 활성 회차도 포함하고 다른 공연의 회차는 포함하지 않는다")
        void periodIncludesPastSchedules() throws Exception {
            jdbc.update("UPDATE p_schedules SET performance_id = 10 WHERE id = 7");
            mockMvc.perform(get("/api/performances/10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.period.from").value("2026-10-30"))
                    .andExpect(jsonPath("$.data.period.to").value("2026-12-02"));
        }

        @Test
        @DisplayName("취소·삭제 회차만 남으면 가격은 유지하면서 period는 null이다")
        void periodIsNullWhenOnlyInactiveSchedulesRemain() throws Exception {
            jdbc.update("UPDATE p_schedules SET operation_status = 'CANCELED' WHERE id = 1");
            jdbc.update("UPDATE p_schedules SET deleted_at = '2026-11-01 12:00:00' WHERE id = 2");
            mockMvc.perform(get("/api/performances/10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.period").value(nullValue()))
                    .andExpect(jsonPath("$.data.gradePrices.length()").value(2));
        }

        @Test
        @DisplayName("활성 회차가 하나이면 기간의 시작과 끝 날짜가 같다")
        void singleSchedulePeriod() throws Exception {
            mockMvc.perform(get("/api/performances/14"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.period.from").value("2026-10-30"))
                    .andExpect(jsonPath("$.data.period.to").value("2026-10-30"));
        }

        @Test
        @DisplayName("공연 정보·공연장·등급별 가격·기간을 명세 형식으로 주고, 시각에는 +09:00을 붙인다")
        void returnsDetail() throws Exception {
            mockMvc.perform(get("/api/performances/10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(10))
                    .andExpect(jsonPath("$.data.venue.id").value(1))
                    .andExpect(jsonPath("$.data.venue.name").value("서울 공연장"))
                    .andExpect(jsonPath("$.data.venue.address").value("서울특별시 중구 예시로 10"))
                    .andExpect(jsonPath("$.data.venue.region").value("서울"))
                    .andExpect(jsonPath("$.data.title").value("뮤지컬 Alpha"))
                    .andExpect(jsonPath("$.data.genre").value("MUSICAL"))
                    .andExpect(jsonPath("$.data.ageRating").value("12"))
                    .andExpect(jsonPath("$.data.runningTime").value(150))
                    .andExpect(jsonPath("$.data.castInfo").value("배우 A, 배우 B"))
                    .andExpect(jsonPath("$.data.description").value("공연 상세 안내"))
                    .andExpect(jsonPath("$.data.posterUrl").value("https://example.com/10.jpg"))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.publishedAt").value("2026-10-20T10:00:00+09:00"))
                    .andExpect(jsonPath("$.data.period.from").value("2026-12-01"))
                    .andExpect(jsonPath("$.data.period.to").value("2026-12-02"))
                    .andExpect(jsonPath("$.data.createdAt").value("2026-10-19T09:00:00+09:00"))
                    .andExpect(jsonPath("$.data.updatedAt").value("2026-10-20T10:00:00+09:00"))
                    // 삭제된 가격 행과 삭제된 등급의 가격은 빠진다
                    .andExpect(jsonPath("$.data.gradePrices.length()").value(2))
                    .andExpect(
                            jsonPath("$.data.gradePrices[0].venueSeatGradeId").value(1))
                    .andExpect(jsonPath("$.data.gradePrices[0].gradeName").value("VIP"))
                    .andExpect(jsonPath("$.data.gradePrices[0].price").value(150000))
                    .andExpect(
                            jsonPath("$.data.gradePrices[1].venueSeatGradeId").value(2))
                    .andExpect(jsonPath("$.data.gradePrices[1].gradeName").value("R"))
                    .andExpect(jsonPath("$.data.gradePrices[1].price").value(100000));
        }

        @Test
        @DisplayName("판매자 ID·version·공개 상태 같은 내부 정보는 응답에 없다")
        void hidesInternalFields() throws Exception {
            mockMvc.perform(get("/api/performances/10"))
                    .andExpect(jsonPath("$.data", not(hasKey("sellerId"))))
                    .andExpect(jsonPath("$.data", not(hasKey("version"))))
                    .andExpect(jsonPath("$.data", not(hasKey("publicationStatus"))));
        }

        @Test
        @DisplayName("활성 회차·가격이 없으면 period는 null, gradePrices는 빈 배열이다")
        void emptyPeriodAndPrices() throws Exception {
            mockMvc.perform(get("/api/performances/13"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.period").value(nullValue()))
                    .andExpect(jsonPath("$.data.castInfo").value(nullValue()))
                    .andExpect(jsonPath("$.data.gradePrices", empty()));
        }

        @Test
        @DisplayName("취소된 공개 공연은 취소 안내를 위해 조회된다")
        void returnsCanceledPublishedPerformance() throws Exception {
            mockMvc.perform(get("/api/performances/22"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(22))
                    .andExpect(jsonPath("$.data.operationStatus").value("CANCELED"))
                    .andExpect(jsonPath("$.data.ageRating").value("19"));
        }

        @Test
        @DisplayName("초안·삭제·삭제된 공연장·없는 공연은 404 PERFORMANCE_NOT_FOUND")
        void notFound() throws Exception {
            for (long id : new long[] {20, 21, 23, 999}) {
                mockMvc.perform(get("/api/performances/" + id))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"))
                        .andExpect(jsonPath("$.path").value("/api/performances/" + id));
            }
        }

        @Test
        @DisplayName("ID가 숫자가 아니거나 0 이하면 400 VALIDATION_ERROR")
        void rejectsInvalidId() throws Exception {
            for (String id : new String[] {"abc", "0", "-1"}) {
                mockMvc.perform(get("/api/performances/" + id))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
    }
}
