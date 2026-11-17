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

/** 이슈 #438의 검색 OR 묶음과 외부 필터 AND를 실제 MVC 바인딩·PostgreSQL 경로로 확인한다. */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicPerformanceSearchIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("공연장명·출연진 전용 검색과 null 출연진을 처리한다")
    void searchesVenueAndCast() throws Exception {
        keyword("배우 A")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        keyword("부산 공연장").andExpect(jsonPath("$.data.content[*].id").value(contains(11)));
        keyword("  aLpHa  ").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        keyword("존재하지 않는 출연진")
                .andExpect(jsonPath("$.data.content", empty()))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("같은 공연이 여러 필드에 걸려도 항목·count는 중복되지 않는다")
    void doesNotDuplicateMatches() throws Exception {
        jdbc.update("UPDATE p_performances SET cast_info = 'Alpha' WHERE id = 10");
        jdbc.update("UPDATE p_venues SET name = 'Alpha 홀' WHERE id = 1");
        keyword("Alpha")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("지역 필터는 코드만 받고 빈값은 기존처럼 필터를 적용하지 않는다")
    void validatesRegionFilter() throws Exception {
        mockMvc.perform(get("/api/performances").queryParam("region", "BUSAN"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(11)));
        for (String invalid : List.of("서울", "서울특별시", "seoul", "UNKNOWN", "0")) {
            mockMvc.perform(get("/api/performances").queryParam("region", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(get("/api/performances").queryParam("region", "  "))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("16개 지역의 이름·옛 이름은 정확히 일치할 때만 지역 공연을 포함한다")
    void searchesRegionAliases() throws Exception {
        clearSearchText();
        String[][] aliases = {
            {"SEOUL", "서울", "서울특별시"},
            {"GYEONGGI", "경기", "경기도"},
            {"INCHEON", "인천", "인천광역시"},
            {"GANGWON", "강원", "강원특별자치도", "강원도"},
            {"CHUNGBUK", "충북", "충청북도"},
            {"CHUNGNAM", "충남", "충청남도"},
            {"DAEJEON", "대전", "대전광역시"},
            {"SEJONG", "세종", "세종특별자치시"},
            {"JEONBUK", "전북", "전북특별자치도", "전라북도"},
            {"JEONNAM_GWANGJU", "전남광주", "광주", "전남", "전남광주통합특별시", "광주광역시", "전라남도"},
            {"GYEONGBUK", "경북", "경상북도"},
            {"GYEONGNAM", "경남", "경상남도"},
            {"DAEGU", "대구", "대구광역시"},
            {"ULSAN", "울산", "울산광역시"},
            {"BUSAN", "부산", "부산광역시"},
            {"JEJU", "제주", "제주특별자치도", "제주도"}
        };
        for (String[] region : aliases) {
            jdbc.update("UPDATE p_venues SET region = ? WHERE id IN (1,2)", region[0]);
            for (int i = 1; i < region.length; i++) {
                keyword(" " + region[i] + " ")
                        .andExpect(jsonPath("$.data.content[*].id").value(contains(12, 11, 10)))
                        .andExpect(jsonPath("$.data.totalElements").value(3));
            }
            keyword(region[0]).andExpect(jsonPath("$.data.content", empty()));
        }
        keyword("경").andExpect(jsonPath("$.data.content", empty()));
        keyword("종로구").andExpect(jsonPath("$.data.content", empty()));
    }

    @Test
    @DisplayName("명시적 지역 필터는 지역 별칭 OR에 의해 우회되지 않는다")
    void combinesExplicitRegionWithKeyword() throws Exception {
        jdbc.update("UPDATE p_performances SET title = '서울 순회 공연' WHERE id = 11");
        mockMvc.perform(get("/api/performances").queryParam("region", "BUSAN").queryParam("keyword", "서울"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(11)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("지역 별칭에도 status·날짜·장르·공개 조건과 페이지/count가 함께 적용된다")
    void combinesAliasesWithOtherFiltersAndPagination() throws Exception {
        mockMvc.perform(get("/api/performances")
                        .queryParam("keyword", "서울")
                        .queryParam("status", "ON_SALE")
                        .queryParam("genre", "PLAY")
                        .queryParam("from", "2027-01-10")
                        .queryParam("to", "2027-01-10"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(12)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/performances").queryParam("keyword", "서울").queryParam("status", "ENDED"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(13)));
        mockMvc.perform(get("/api/performances")
                        .queryParam("keyword", "서울")
                        .queryParam("size", "1")
                        .queryParam("page", "1"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(10)))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        mockMvc.perform(get("/api/performances").queryParam("keyword", "서울").queryParam("from", "2030-01-01"))
                .andExpect(jsonPath("$.data.content", empty()))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/performances")
                        .queryParam("keyword", "서울")
                        .queryParam("status", "ON_SALE,UPCOMING,ENDED"))
                .andExpect(jsonPath("$.data.content[*].id").value(contains(13, 12, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("퍼센트·밑줄은 제목·공연장명·출연진 전체에서 문자 그대로 찾는다")
    void escapesWildcardsInEveryField() throws Exception {
        jdbc.update("UPDATE p_performances SET title = '할인 100%', cast_info = '배우_한명' WHERE id = 10");
        jdbc.update("UPDATE p_venues SET name = '공연장_이름' WHERE id = 2");
        keyword("%").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        keyword("_")
                .andExpect(jsonPath("$.data.content[*].id").value(contains(11, 10)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    private ResultActions keyword(String keyword) throws Exception {
        return mockMvc.perform(get("/api/performances").queryParam("keyword", keyword))
                .andExpect(status().isOk());
    }

    private void clearSearchText() {
        jdbc.update("UPDATE p_venues SET name = '공연홀'");
        jdbc.update("UPDATE p_performances SET title = '표준 공연', cast_info = NULL");
    }
}
