package com.sparta.ticket.performance.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/performances" + query));
    }

    @Nested
    @DisplayName("GET /api/performances")
    class Search {

        @Test
        @DisplayName("공개되고 삭제·취소되지 않은 공연만 최근 공개 순으로 나온다(초안·삭제·취소·삭제된 공연장 제외)")
        void listsOnlyPublishedActivePerformances() throws Exception {
            list("").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(14, 13, 12, 11, 10)))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.totalElements").value(5));
        }

        @Test
        @DisplayName("항목은 명세 필드만 담고, 기간은 삭제·취소 회차를 뺀 활성 회차의 첫날·마지막 날이다")
        void itemShape() throws Exception {
            list("?keyword=Alpha")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].id").value(10))
                    .andExpect(jsonPath("$.data.items[0].venue.id").value(1))
                    .andExpect(jsonPath("$.data.items[0].venue.name").value("서울 공연장"))
                    .andExpect(jsonPath("$.data.items[0].venue.address").value("서울특별시 중구 예시로 10"))
                    .andExpect(jsonPath("$.data.items[0].venue.region").value("서울"))
                    .andExpect(jsonPath("$.data.items[0].title").value("뮤지컬 Alpha"))
                    .andExpect(jsonPath("$.data.items[0].genre").value("MUSICAL"))
                    .andExpect(jsonPath("$.data.items[0].posterUrl").value("https://example.com/10.jpg"))
                    .andExpect(jsonPath("$.data.items[0].operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.items[0].period.from").value("2026-12-01"))
                    .andExpect(jsonPath("$.data.items[0].period.to").value("2026-12-02"))
                    .andExpect(jsonPath("$.data.items[0]", not(hasKey("sellerId"))))
                    .andExpect(jsonPath("$.data.items[0]", not(hasKey("version"))))
                    .andExpect(jsonPath("$.data.items[0]", not(hasKey("publicationStatus"))));
        }

        @Test
        @DisplayName("활성 회차가 없는 공연은 period가 null이고, 포스터가 없으면 posterUrl이 null이다")
        void nullPeriodAndPoster() throws Exception {
            list("?genre=SPORTS")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(13)))
                    .andExpect(jsonPath("$.data.items[0]", hasKey("period")))
                    .andExpect(jsonPath("$.data.items[0].period").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[0]", hasKey("posterUrl")))
                    .andExpect(jsonPath("$.data.items[0].posterUrl").value(nullValue()));
        }

        @Test
        @DisplayName("장르로 거른다")
        void filtersByGenre() throws Exception {
            list("?genre=MUSICAL")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(14, 10)))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("공연장 지역으로 거른다")
        void filtersByRegion() throws Exception {
            list("?region=부산")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(14, 11)))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("검색어는 공연명 부분 일치이며 대소문자와 앞뒤 공백을 무시한다")
        void filtersByKeyword() throws Exception {
            list("?keyword= gAmMa ").andExpect(jsonPath("$.data.items[*].id").value(contains(12)));
            list("?keyword=뮤지컬").andExpect(jsonPath("$.data.items[*].id").value(contains(14, 10)));
        }

        @Test
        @DisplayName("검색어의 %와 _는 와일드카드가 아니라 글자로 찾는다")
        void keywordWildcardsAreLiteral() throws Exception {
            // Note: URL 템플릿에 %를 직접 쓰면 MockMvc가 한 번 더 인코딩하므로 param으로 넘긴다.
            mockMvc.perform(get("/api/performances").param("keyword", "%"))
                    .andExpect(jsonPath("$.data.items", empty()));
            mockMvc.perform(get("/api/performances").param("keyword", "_"))
                    .andExpect(jsonPath("$.data.items", empty()));
        }

        @Test
        @DisplayName("빈 검색어·지역은 필터를 적용하지 않는다")
        void blankFiltersAreIgnored() throws Exception {
            mockMvc.perform(get("/api/performances").param("keyword", "  ").param("region", ""))
                    .andExpect(jsonPath("$.data.totalElements").value(5));
        }

        @Test
        @DisplayName("기간 필터는 시작일(KST)이 [from, to]에 드는 활성 회차가 있는 공연이다")
        void filtersByScheduleDate() throws Exception {
            list("?from=2026-12-01&to=2026-12-01")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(10)));
            list("?from=2026-12-02&to=2026-12-02")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(10)));
            list("?from=2026-12-15").andExpect(jsonPath("$.data.items[*].id").value(contains(12, 11)));
            list("?to=2026-10-31").andExpect(jsonPath("$.data.items[*].id").value(contains(14)));
        }

        @Test
        @DisplayName("취소·삭제된 회차의 날짜로는 걸리지 않는다")
        void dateFilterIgnoresInactiveSchedules() throws Exception {
            list("?from=2026-11-25&to=2026-11-25")
                    .andExpect(jsonPath("$.data.items", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
            list("?from=2026-12-31&to=2026-12-31").andExpect(jsonPath("$.data.items", empty()));
        }

        @Test
        @DisplayName("필터를 함께 쓰면 모두 만족하는 공연만 나온다")
        void combinesFilters() throws Exception {
            list("?genre=MUSICAL&region=서울&from=2026-12-01&to=2026-12-31")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(10)));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC는 마감 전 활성 회차의 가장 이른 오픈 순이고, 그런 회차가 없는 공연은 마지막이다")
        void sortsByNextBookingOpen() throws Exception {
            // 10은 취소·삭제 회차의 오픈(10-02)이 가장 이르지만 활성 회차 기준(11-10)으로 정렬된다. 14(마감 지남)와 13(회차 없음)은 마지막이다.
            list("?sort=BOOKING_OPEN_ASC")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(12, 11, 10, 14, 13)));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC의 마감 판단은 현재 시각을 따른다")
        void bookingOpenSortUsesCurrentTime() throws Exception {
            // 12-02 18:30이면 10의 두 회차(12-01·12-02 18:00 마감)가 모두 마감돼 마지막 묶음(14, 13, 10, ID 역순)으로 간다.
            // 11(12-20 마감)과 12(2027-01-10 마감)는 아직 판매 중이다.
            clock.set(LocalDateTime.of(2026, 12, 2, 18, 30));
            list("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(12, 11, 14, 13, 10)));
        }

        @Test
        @DisplayName("BOOKING_OPEN_ASC에서 마감 시각과 현재 시각이 같은 회차는 마감으로 본다")
        void bookingCloseAtNowIsClosed() throws Exception {
            // 11의 유일한 회차가 12-20 18:00에 마감된다. 10도 이미 마감이라 마지막 묶음(14, 13, 11, 10)에 들어간다.
            clock.set(LocalDateTime.of(2026, 12, 20, 18, 0));
            list("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(12, 14, 13, 11, 10)));
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
            list("").andExpect(jsonPath("$.data.items[*].id").value(contains(14, 13, 12, 11, 10, 16, 15, 17)))
                    .andExpect(jsonPath("$.data.totalElements").value(8));
            list("?genre=PLAY&region=서울")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(12, 16, 15)))
                    .andExpect(jsonPath("$.data.items[1].period").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[2].period").value(nullValue()))
                    .andExpect(jsonPath("$.data.totalElements").value(3));
            list("?from=2026-12-10&to=2026-12-11")
                    .andExpect(jsonPath("$.data.items", empty()))
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
            list("?sort=BOOKING_OPEN_ASC")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(12, 17, 11, 10, 16, 15, 14, 13)));
        }

        @Test
        @DisplayName("page와 size로 나눠 주고 totalElements는 전체 건수다")
        void paginates() throws Exception {
            list("?page=1&size=2")
                    .andExpect(jsonPath("$.data.items[*].id").value(contains(12, 11)))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.size").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(5));
            list("?page=5&size=2")
                    .andExpect(jsonPath("$.data.items", empty()))
                    .andExpect(jsonPath("$.data.totalElements").value(5));
            list("?size=100").andExpect(status().isOk());
        }

        @Test
        @DisplayName("from이 to보다 늦으면 400 INVALID_TIME_RANGE")
        void rejectsReversedDateRange() throws Exception {
            list("?from=2026-12-02&to=2026-12-01")
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
                list(query)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
    }

    @Nested
    @DisplayName("GET /api/performances/{performanceId}")
    class Detail {

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
        @DisplayName("ID가 숫자가 아니면 400 VALIDATION_ERROR")
        void rejectsNonNumericId() throws Exception {
            mockMvc.perform(get("/api/performances/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }
}
