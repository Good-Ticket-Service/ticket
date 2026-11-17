package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이슈 #441 검색용 텍스트(search_text). schema.sql의 트리거가 모든 쓰기 경로에서 값을 채우고,
 * 키워드 조건이 이슈 #442의 필드별 공백 제거 비교와 같은 공연을 고르며 필드 경계를 넘어 맞지 않는지 확인한다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PerformanceSearchTextIntegrationTest extends PerformanceIntegrationTest {

    private static final String SEP = "\u001F";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PerformanceStore performanceStore;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("SQL로 넣은 공연도 제목·출연진·공연장명을 공백 제거·소문자로 바꿔 구분 문자로 잇는다")
    void fillsSearchTextOnSqlInsert() {
        assertThat(searchText(10)).isEqualTo("뮤지컬alpha" + SEP + "배우a,배우b" + SEP + "서울공연장");
        // 출연진이 없으면 빈 칸으로 자리만 둔다
        assertThat(searchText(11)).isEqualTo("콘서트beta" + SEP + SEP + "부산공연장");
        // 삭제된 공연, 삭제된 공연장의 공연도 채운다
        assertThat(searchText(21)).isEqualTo("뮤지컬deleted" + SEP + SEP + "서울공연장");
        assertThat(searchText(23)).isEqualTo("뮤지컬lostvenue" + SEP + SEP + "삭제된공연장");
    }

    @Test
    @DisplayName("Entity로 등록한 공연도 트리거가 채운다")
    void fillsSearchTextOnJpaInsert() {
        Long id = transactions.execute(status -> performanceStore
                .save(Performance.createDraft(
                        900L, 2L, "피아노 독주회", Genre.CLASSICAL, AgeRating.ALL, null, 90, "Kim Pianist", "소개", null))
                .getId());

        assertThat(searchText(id)).isEqualTo("피아노독주회" + SEP + "kimpianist" + SEP + "부산공연장");
    }

    @Test
    @DisplayName("제목·출연진·공연장을 바꾸면 다시 계산하고, 공연장명이 바뀌면 그 공연장의 공연을 모두 다시 계산한다")
    void refreshesSearchTextOnWrites() {
        jdbc.update("UPDATE p_performances SET title = 'New Title', cast_info = '새 배우' WHERE id = 10");
        assertThat(searchText(10)).isEqualTo("newtitle" + SEP + "새배우" + SEP + "서울공연장");

        jdbc.update("UPDATE p_performances SET venue_id = 2 WHERE id = 10");
        assertThat(searchText(10)).isEqualTo("newtitle" + SEP + "새배우" + SEP + "부산공연장");

        jdbc.update("UPDATE p_venues SET name = '새 공연장 이름' WHERE id = 1");
        assertThat(searchText(12)).isEqualTo("연극gamma" + SEP + SEP + "새공연장이름");
        assertThat(searchText(21)).isEqualTo("뮤지컬deleted" + SEP + SEP + "새공연장이름");
        // 다른 공연장으로 옮긴 공연은 바뀌지 않는다
        assertThat(searchText(10)).isEqualTo("newtitle" + SEP + "새배우" + SEP + "부산공연장");

        // 검색에 쓰지 않는 컬럼만 바꾸면 값이 그대로다
        jdbc.update("UPDATE p_venues SET address = '다른 주소' WHERE id = 2");
        jdbc.update("UPDATE p_performances SET description = '다른 안내' WHERE id = 11");
        assertThat(searchText(11)).isEqualTo("콘서트beta" + SEP + SEP + "부산공연장");
    }

    @Test
    @DisplayName("필드 경계를 넘는 문자열은 일치로 보지 않는다")
    void doesNotMatchAcrossFields() throws Exception {
        jdbc.update("UPDATE p_performances SET title = '가나 다', cast_info = '라마 바' WHERE id = 10");
        jdbc.update("UPDATE p_venues SET name = '사아 자' WHERE id = 1");

        // 제목 끝 + 출연진 앞, 출연진 끝 + 공연장명 앞, 세 필드 전체
        for (String keyword : List.of("다라", "바사", "가나다라마바사아자")) {
            search(keyword)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
        // 한 필드 안에서는 띄어쓰기를 넘어 맞는다(이슈 #442)
        search("나다").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        search("마바").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        search("아자").andExpect(jsonPath("$.data.content[*].id").value(hasItem(10)));
    }

    @Test
    @DisplayName("검색용 텍스트 비교는 필드별 공백 제거 비교와 같은 공연을 고른다")
    void matchesFieldwiseComparison() {
        jdbc.update("UPDATE p_performances SET cast_info = 'Alpha 합창단, 배우_C 100%!' WHERE id = 12");
        jdbc.update("UPDATE p_venues SET name = 'Gamma Hall' WHERE id = 2");
        List<String> keywords = List.of(
                "alpha", "ALPHA", "뮤지컬", "공연장", "서울 공연장", "배우A", "배우_c", "_", "%", "!", "100%!", "a,배",
                "gamma", "hall", "부산", "delta", "없는말", "lostvenue");

        int matched = 0;
        for (String keyword : keywords) {
            String pattern = containsPattern(keyword);
            List<Long> fieldwise = jdbc.queryForList(
                    "SELECT p.id FROM p_performances p JOIN p_venues v ON v.id = p.venue_id"
                            + " WHERE lower(replace(p.title, ' ', '')) LIKE ? ESCAPE '!'"
                            + " OR lower(replace(v.name, ' ', '')) LIKE ? ESCAPE '!'"
                            + " OR lower(replace(p.cast_info, ' ', '')) LIKE ? ESCAPE '!' ORDER BY p.id",
                    Long.class,
                    pattern,
                    pattern,
                    pattern);
            List<Long> searchText = jdbc.queryForList(
                    "SELECT p.id FROM p_performances p WHERE p.search_text LIKE ? ESCAPE '!' ORDER BY p.id",
                    Long.class,
                    pattern);
            assertThat(searchText).as(keyword).isEqualTo(fieldwise);
            matched += fieldwise.size();
        }
        assertThat(matched).isPositive();
    }

    private String searchText(long performanceId) {
        return jdbc.queryForObject(
                "SELECT search_text FROM p_performances WHERE id = ?", String.class, performanceId);
    }

    private ResultActions search(String keyword) throws Exception {
        return mockMvc.perform(get("/api/performances")
                .queryParam("keyword", keyword)
                .queryParam("status", "ON_SALE,UPCOMING,ENDED"));
    }

    /** 저장소와 같은 패턴: 스페이스를 지우고(KeywordParameter) 소문자로 바꾼 뒤 %·_·!를 이스케이프한다. */
    private static String containsPattern(String keyword) {
        String normalized = keyword.replace(" ", "").toLowerCase(Locale.ROOT);
        return "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
