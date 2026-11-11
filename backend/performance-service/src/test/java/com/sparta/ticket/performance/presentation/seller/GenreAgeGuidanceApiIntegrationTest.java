package com.sparta.ticket.performance.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 실제 등록·가격·회차·공개·조회 경로로 장르 분리와 관람 안내의 호환성을 검증한다. */
@Sql(
        value = {"/sql/truncate.sql", "/sql/seller-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class GenreAgeGuidanceApiIntegrationTest extends PerformanceIntegrationTest {
    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder seller(MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, "501")
                .header(UserHeaders.USERNAME, "seller501")
                .header(UserHeaders.ROLE, "SELLER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions register(String genre, Map<String, Object> age) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "venueId",
                301,
                "title",
                "장르 안내 검증 " + genre,
                "genre",
                genre,
                "runningTime",
                120,
                "description",
                "관람 안내 검증",
                "posterUrl",
                "https://example.com/show.jpg"));
        body.putAll(age);
        return mockMvc.perform(seller(post("/api/seller/performances")).content(mapper.writeValueAsString(body)));
    }

    private long id(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();
    }

    private void publish(long id) throws Exception {
        mockMvc.perform(seller(put("/api/seller/performances/{id}/grade-prices", id))
                        .header("If-Match", "0")
                        .content("""
                                {"gradePrices":[{"venueSeatGradeId":301,"price":10000},
                                {"venueSeatGradeId":302,"price":9000},{"venueSeatGradeId":303,"price":8000}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(seller(post("/api/seller/performances/{id}/schedules", id))
                        .content("""
                        {"startAt":"2026-12-20T19:00:00+09:00","bookingOpenAt":"2026-11-10T10:00:00+09:00",
                         "bookingCloseAt":"2026-12-20T18:00:00+09:00"}
                        """))
                .andExpect(status().isCreated());
        mockMvc.perform(seller(post("/api/seller/performances/{id}/publication", id))
                        .header("If-Match", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void registersAndFindsClassicalSeparatelyFromLegacyConcert() throws Exception {
        long concert = id(register("CONCERT", Map.of("ageRating", "ALL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ageRating").value("ALL"))
                .andExpect(jsonPath("$.data.ageGuidance").value(nullValue())));
        long classical = id(register("CLASSICAL", Map.of("ageGuidance", "만 8세 이상"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ageRating").hasJsonPath())
                .andExpect(jsonPath("$.data.ageRating").value(nullValue()))
                .andExpect(jsonPath("$.data.ageGuidance").value("만 8세 이상")));
        publish(concert);
        publish(classical);
        for (Map.Entry<String, Long> entry :
                Map.of("CONCERT", concert, "CLASSICAL", classical).entrySet()) {
            mockMvc.perform(get("/api/performances")
                            .param("genre", entry.getKey())
                            .param("keyword", "장르 안내 검증"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id")
                            .value(contains(entry.getValue().intValue())));
        }
        mockMvc.perform(get("/api/performances/{id}", classical))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ageRating").hasJsonPath())
                .andExpect(jsonPath("$.data.genre").value("CLASSICAL"))
                .andExpect(jsonPath("$.data.ageRating").value(nullValue()))
                .andExpect(jsonPath("$.data.ageGuidance").value("만 8세 이상"));
        mockMvc.perform(get("/api/performances/{id}", concert))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ageRating").value("ALL"))
                .andExpect(jsonPath("$.data.ageGuidance").value(nullValue()));
    }

    @Test
    void acceptsEveryGenre() throws Exception {
        for (Genre genre : Genre.values()) {
            register(genre.name(), Map.of("ageRating", "ALL"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.genre").value(genre.name()));
        }
    }

    @Test
    void preservesGuidanceAndTrimsOnlyItsEdges() throws Exception {
        for (String guidance : List.of("36개월 이상", "초등학생 이상", "전체 관람가", "만 8세 이상", "가".repeat(100))) {
            long id = id(register("CLASSICAL", Map.of("ageGuidance", "\u00a0　 " + guidance + "　\u00a0 "))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.ageRating").value(nullValue()))
                    .andExpect(jsonPath("$.data.ageGuidance").value(guidance)));
            assertThat(jdbc.queryForObject("SELECT age_guidance FROM p_performances WHERE id=?", String.class, id))
                    .isEqualTo(guidance);
        }
    }

    @Test
    void retainsBothFieldsWithoutConvertingThem() throws Exception {
        register("CLASSICAL", Map.of("ageRating", "12", "ageGuidance", "초등학생 이상"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ageRating").value("12"))
                .andExpect(jsonPath("$.data.ageGuidance").value("초등학생 이상"));
    }

    @Test
    void rejectsBlankTooLongMissingAndInvalidAgeInputsWithoutSaving() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM p_performances", Integer.class);
        for (Map<String, Object> age : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("ageGuidance", ""),
                Map.of("ageGuidance", " \t\n　\u00a0"),
                Map.of("ageGuidance", "가".repeat(101)),
                Map.of("ageRating", "ALL", "ageGuidance", ""),
                Map.of("ageRating", "18", "ageGuidance", "만 8세 이상"))) {
            register("CLASSICAL", age)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        Map<String, Object> bothNull = new LinkedHashMap<>();
        bothNull.put("ageRating", null);
        bothNull.put("ageGuidance", null);
        register("CLASSICAL", bothNull).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_performances", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void keepsAllLegacyRatingsAndAllowsExplicitNullGuidance() throws Exception {
        for (String rating : List.of("ALL", "12", "15", "19")) {
            Map<String, Object> age = new LinkedHashMap<>();
            age.put("ageRating", rating);
            age.put("ageGuidance", null);
            register("CONCERT", age)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.ageRating").value(rating));
        }
    }

    @Test
    void readsExistingUnknownAgeWithoutAssumingAllAges() throws Exception {
        jdbc.update("UPDATE p_performances SET age_rating=NULL WHERE id=311");
        mockMvc.perform(get("/api/performances/311"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ageRating").value(nullValue()))
                .andExpect(jsonPath("$.data.ageGuidance").value(nullValue()));
    }
}
