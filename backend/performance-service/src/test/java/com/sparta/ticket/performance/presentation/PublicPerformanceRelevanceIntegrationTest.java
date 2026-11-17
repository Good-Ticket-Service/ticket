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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 이슈 #442의 띄어쓰기 무시 검색과 관련도 순 정렬을 실제 MVC 바인딩·PostgreSQL 경로로 확인한다.
 * 픽스처 기본 목록(판매중·판매예정)은 공연 12·11·10(최신순)이고, 세 상태 모두면 14·13도 포함한다.
 * 공연장 1(SEOUL)은 공연 10·12·13, 공연장 2(BUSAN)는 공연 11·14다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicPerformanceRelevanceIntegrationTest extends PerformanceIntegrationTest {

    private static final String ALL_STATUSES = "ON_SALE,UPCOMING,ENDED";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("검색어와 필드의 띄어쓰기를 무시하고 부분 일치로 찾는다")
    void ignoresSpacesOnBothSides() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '시카고' WHERE id = 12");
        jdbc.update("UPDATE p_performances SET title = '레 베카' WHERE id = 10");
        for (String keyword : List.of("시 카고", "시카 고", " 시 카 고 ", "시카", "카고")) {
            search(keyword).andExpect(jsonPath("$.data.content[*].id").value(contains(12)));
        }
        search("베카").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        search("레베 카").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
    }

    @Test
    @DisplayName("영문 대소문자를 무시하고, 공연장명·출연진에도 띄어쓰기 무시를 적용한다")
    void ignoresCaseAndSpacesInVenueAndCast() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = 'The Phantom' WHERE id = 12");
        jdbc.update("UPDATE p_performances SET cast_info = '홍 길동, 김철수' WHERE id = 10");
        jdbc.update("UPDATE p_venues SET name = '부산 문화 회관' WHERE id = 2");
        search("phantom").andExpect(jsonPath("$.data.content[*].id").value(contains(12)));
        search("THEPHANTOM").andExpect(jsonPath("$.data.content[*].id").value(contains(12)));
        search("홍길동").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        search("문화회관").andExpect(jsonPath("$.data.content[*].id").value(contains(11)));
    }

    @Test
    @DisplayName("지역 이름 일치에도 띄어쓰기를 무시한다")
    void ignoresSpacesInRegionAlias() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_venues SET region = 'JEONNAM_GWANGJU' WHERE id = 1");
        search("전남 광주")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("스페이스만 있는 검색어는 검색어가 없는 것으로 본다")
    void treatsSpaceOnlyKeywordAsAbsent() throws Exception {
        search("   ")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("관련도 순은 제목 같음 → 제목 시작 → 제목 포함 → 출연진 → 공연장명 순이다")
    void ordersByTextGrades() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '시 카고' WHERE id = 13");
        jdbc.update("UPDATE p_performances SET title = '시카고 25주년' WHERE id = 10");
        jdbc.update("UPDATE p_performances SET title = '뮤지컬 시카고' WHERE id = 14");
        jdbc.update("UPDATE p_performances SET cast_info = '시카고 합창단' WHERE id = 12");
        jdbc.update("UPDATE p_venues SET name = '시카고 홀' WHERE id = 2");
        mockMvc.perform(relevance("시카고").queryParam("status", ALL_STATUSES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(contains(13, 10, 14, 12, 11)))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.sort").value("RELEVANCE"));
        // 최신순과 목록 대상·전체 개수는 같고 순서만 다르다.
        mockMvc.perform(get("/api/performances").queryParam("keyword", "시카고").queryParam("status", ALL_STATUSES))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 13, 12, 11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    @DisplayName("지역 이름으로만 걸린 공연은 마지막이고, 같은 등급은 최근 공개 순이다")
    void ordersRegionOnlyLastAndBreaksTiesByLatest() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '서울 이야기' WHERE id = 14");
        jdbc.update("UPDATE p_performances SET cast_info = '서울 합창단' WHERE id = 11");
        mockMvc.perform(relevance("서울").queryParam("status", ALL_STATUSES))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(14, 11, 13, 12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(5));
        mockMvc.perform(relevance("서울")
                        .queryParam("status", ALL_STATUSES)
                        .queryParam("size", "2")
                        .queryParam("page", "1"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(13, 12)))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    @DisplayName("관련도 순에도 판매 상태·장르 필터가 함께 적용된다")
    void combinesRelevanceWithFilters() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '시카고 25주년' WHERE id = 10");
        jdbc.update("UPDATE p_performances SET title = '뮤지컬 시카고' WHERE id = 14");
        jdbc.update("UPDATE p_performances SET title = '시카고' WHERE id = 12");
        mockMvc.perform(relevance("시카고").queryParam("status", ALL_STATUSES).queryParam("genre", "MUSICAL"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10, 14)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mockMvc.perform(relevance("시카고"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("같은 등급에서 공개 시각까지 같으면 ID 역순이다")
    void breaksSamePublishedAtTiesById() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET published_at = '2026-10-25 10:00:00' WHERE id IN (10, 12, 13)");
        mockMvc.perform(relevance("서울").queryParam("status", ALL_STATUSES))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(13, 12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("관련도 순 등급 비교에서도 퍼센트·밑줄·느낌표는 문자 그대로다")
    void escapesWildcardsInGrades() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '100% 할인' WHERE id = 10");
        jdbc.update("UPDATE p_performances SET title = '1000 할인' WHERE id = 12");
        mockMvc.perform(relevance("100%"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // 밑줄: 제목 시작(2)과 포함(3)에서 한 글자 와일드카드로 쓰이지 않는다. 'a1b'는 걸리지 않는다.
        jdbc.update("UPDATE p_performances SET title = 'a_b 공연' WHERE id = 10");
        jdbc.update("UPDATE p_performances SET title = '특별 a_b' WHERE id = 11");
        jdbc.update("UPDATE p_performances SET title = 'a1b 공연' WHERE id = 12");
        mockMvc.perform(relevance("a_b"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10, 11)))
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // 느낌표는 이스케이프 문자 자체라 따로 이스케이프한다. 제목 같음(1)이 맨 앞이다.
        jdbc.update("UPDATE p_performances SET title = 'a!b' WHERE id = 10");
        jdbc.update("UPDATE p_performances SET title = '특별 a!b' WHERE id = 11");
        jdbc.update("UPDATE p_performances SET title = 'ab' WHERE id = 12");
        mockMvc.perform(relevance("a!b"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10, 11)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("공백을 지운 뒤 비교하므로 단어 경계를 넘는 일치도 찾는다(의도된 동작)")
    void matchesAcrossWordBoundaries() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '피아노 독주회' WHERE id = 12");
        mockMvc.perform(relevance("노독"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("관련도 순에도 명시적 지역·날짜 필터와 페이지·전체 개수가 함께 적용된다")
    void combinesRelevanceWithRegionDateAndPaging() throws Exception {
        clearSearchText();
        jdbc.update("UPDATE p_performances SET title = '서울 순회 공연' WHERE id = 11");
        mockMvc.perform(relevance("서울").queryParam("region", "BUSAN"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(11)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        // 공연 11은 제목 시작(2), 공연 12·10은 지역 이름(6)이다.
        mockMvc.perform(relevance("서울"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(11, 12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mockMvc.perform(relevance("서울").queryParam("size", "1").queryParam("page", "2"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        mockMvc.perform(relevance("서울").queryParam("from", "2030-01-01"))
                .andExpect(jsonPath("$.data.content", empty()))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("검색어가 없거나 스페이스뿐이면 관련도 순은 400이다")
    void rejectsRelevanceWithoutKeyword() throws Exception {
        mockMvc.perform(get("/api/performances").queryParam("sort", "RELEVANCE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(relevance("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ResultActions search(String keyword) throws Exception {
        return mockMvc.perform(get("/api/performances").queryParam("keyword", keyword))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder relevance(String keyword) {
        return get("/api/performances").queryParam("keyword", keyword).queryParam("sort", "RELEVANCE");
    }

    private void clearSearchText() {
        jdbc.update("UPDATE p_venues SET name = '공연홀'");
        jdbc.update("UPDATE p_performances SET title = '표준 공연', cast_info = NULL");
    }
}
