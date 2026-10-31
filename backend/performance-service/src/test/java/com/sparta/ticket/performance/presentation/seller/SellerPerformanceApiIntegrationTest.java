package com.sparta.ticket.performance.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자 공연 명령(공연 초안 등록, 등급별 가격 설정)을 독립 fixture와 실제 PostgreSQL로 검증한다.
 * fixture 공연 310은 판매자 501의 초안(version 2, 가격 VIP·R)이고, 좌석 S는 판매 제외라 S 가격은 생략할 수 있다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/seller-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SellerPerformanceApiIntegrationTest extends PerformanceIntegrationTest {

    private static final long SELLER = 501;
    private static final long VENUE = 301;
    private static final long DRAFT = 310;
    private static final long PUBLISHED = 311;
    private static final long OTHER_SELLERS = 312;
    private static final long DELETED = 313;
    private static final long VIP = 301;
    private static final long R = 302;
    private static final long S = 303;
    /** draftBody에서 필드를 빼라는 표시. */
    private static final Object NULL = new Object();

    private static final String DATE_TIME_KST = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+09:00";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static MockHttpServletRequestBuilder as(long userId, String role, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "seller" + userId)
                .header(UserHeaders.ROLE, role)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions registerDraft(String body) throws Exception {
        return mockMvc.perform(
                as(SELLER, "SELLER", post("/api/seller/performances")).content(body));
    }

    private ResultActions replacePrices(long userId, Object performanceId, String ifMatch, String body)
            throws Exception {
        MockHttpServletRequestBuilder request =
                as(userId, "SELLER", put("/api/seller/performances/" + performanceId + "/grade-prices"));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request.content(body));
    }

    private ResultActions replacePrices(Object performanceId, String ifMatch, String body) throws Exception {
        return replacePrices(SELLER, performanceId, ifMatch, body);
    }

    /** {등급 ID, 가격} 쌍으로 요청 본문을 만든다. */
    private static String prices(long... gradeIdAndPrice) {
        StringBuilder json = new StringBuilder("{\"gradePrices\":[");
        for (int i = 0; i < gradeIdAndPrice.length; i += 2) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"venueSeatGradeId\":")
                    .append(gradeIdAndPrice[i])
                    .append(",\"price\":")
                    .append(gradeIdAndPrice[i + 1])
                    .append("}");
        }
        return json.append("]}").toString();
    }

    private String draftBody(Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "venueId", VENUE,
                "title", "공연 A 서울",
                "genre", "MUSICAL",
                "ageRating", "12",
                "runningTime", 120));
        body.putAll(overrides);
        body.values().removeIf(value -> value == NULL);
        return objectMapper.writeValueAsString(body);
    }

    private long performanceVersion(long performanceId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM p_performances WHERE id = ?", Long.class, performanceId);
    }

    /** 살아 있는 가격 행을 "등급:가격"으로, 등급 ID 순서로 읽는다. */
    private List<String> livePrices(long performanceId) {
        return jdbcTemplate.queryForList(
                "SELECT venue_seat_grade_id || ':' || price FROM p_performance_grade_prices"
                        + " WHERE performance_id = ? AND deleted_at IS NULL ORDER BY venue_seat_grade_id",
                String.class,
                performanceId);
    }

    private Map<String, Object> schedule(long scheduleId) {
        return jdbcTemplate.queryForMap(
                "SELECT seat_config_version, seat_setup_status, seat_setup_request_id, seat_setup_error,"
                        + " seat_setup_attempts, seat_setup_next_attempt_at, operation_status, cancel_reason, version"
                        + " FROM p_schedules WHERE id = ?",
                scheduleId);
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Nested
    @DisplayName("POST /api/seller/performances 공연 초안 등록")
    class RegisterDraft {

        @Test
        @DisplayName("201과 Location을 주고, 판매자는 로그인 사용자이며 DRAFT·ACTIVE·version 0으로 저장한다")
        void registersDraft() throws Exception {
            MvcResult result = registerDraft(draftBody(Map.of(
                            "title", " 공연 A 서울 ",
                            "castInfo", "배우 A, 배우 B",
                            "description", "   ",
                            "posterUrl", "https://example.com/poster.jpg",
                            "sellerId", 999)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.venue.id").value(VENUE))
                    .andExpect(jsonPath("$.data.venue.name").value("판매 공연장"))
                    .andExpect(jsonPath("$.data.venue.address").value("서울특별시 중구 예시로 10"))
                    .andExpect(jsonPath("$.data.venue.region").value("서울"))
                    .andExpect(jsonPath("$.data.title").value("공연 A 서울"))
                    .andExpect(jsonPath("$.data.genre").value("MUSICAL"))
                    .andExpect(jsonPath("$.data.ageRating").value("12"))
                    .andExpect(jsonPath("$.data.runningTime").value(120))
                    .andExpect(jsonPath("$.data.castInfo").value("배우 A, 배우 B"))
                    .andExpect(jsonPath("$.data.description").value(nullValue()))
                    .andExpect(jsonPath("$.data.posterUrl").value("https://example.com/poster.jpg"))
                    .andExpect(jsonPath("$.data.publicationStatus").value("DRAFT"))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.publishedAt").value(nullValue()))
                    .andExpect(jsonPath("$.data.period").value(nullValue()))
                    .andExpect(jsonPath("$.data.version").value(0))
                    .andExpect(jsonPath("$.data.createdAt", matchesPattern(DATE_TIME_KST)))
                    .andExpect(jsonPath("$.data.updatedAt", matchesPattern(DATE_TIME_KST)))
                    .andExpect(jsonPath("$.data.sellerId").value(SELLER))
                    .andReturn();

            long id = objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .at("/data/id")
                    .asLong();
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/seller/performances/" + id);
            assertThat(jdbcTemplate.queryForMap(
                            "SELECT seller_id, title, description, created_by FROM p_performances WHERE id = ?", id))
                    .containsEntry("seller_id", SELLER)
                    .containsEntry("title", "공연 A 서울")
                    .containsEntry("description", null)
                    .containsEntry("created_by", "seller501");
        }

        @Test
        @DisplayName("선택 항목을 모두 빼도 된다. 빠진 항목은 null이다")
        void registersWithoutOptionalFields() throws Exception {
            registerDraft(draftBody(Map.of("ageRating", "ALL", "genre", "SPORTS")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.ageRating").value("ALL"))
                    .andExpect(jsonPath("$.data.castInfo").value(nullValue()))
                    .andExpect(jsonPath("$.data.posterUrl").value(nullValue()));
        }

        @Test
        @DisplayName("posterUrl이 비었거나 공백만(전각·NBSP 포함)이면 null로 저장하고, 앞뒤 공백은 지운다")
        void storesBlankPosterUrlAsNull() throws Exception {
            for (String blank : new String[] {"", "   ", "　", " 　 ", " ", "  　"}) {
                registerDraft(draftBody(Map.of("posterUrl", blank)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.posterUrl").value(nullValue()));
            }
            for (String padded : new String[] {" https://example.com/a.jpg ", " https://example.com/a.jpg "}) {
                registerDraft(draftBody(Map.of("posterUrl", padded)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.posterUrl").value("https://example.com/a.jpg"));
            }
        }

        @Test
        @DisplayName("venueId·runningTime은 JSON 정수만 받는다. 소수·문자열은 400 VALIDATION_ERROR다")
        void rejectsNonIntegerNumbers() throws Exception {
            for (String body : new String[] {
                "{\"venueId\":301.9,\"title\":\"t\",\"genre\":\"PLAY\",\"ageRating\":\"ALL\",\"runningTime\":120}",
                "{\"venueId\":\"301\",\"title\":\"t\",\"genre\":\"PLAY\",\"ageRating\":\"ALL\",\"runningTime\":120}",
                "{\"venueId\":301,\"title\":\"t\",\"genre\":\"PLAY\",\"ageRating\":\"ALL\",\"runningTime\":120.5}",
                "{\"venueId\":301,\"title\":\"t\",\"genre\":\"PLAY\",\"ageRating\":\"ALL\",\"runningTime\":3000000000}"
            }) {
                expectError(registerDraft(body), 400, "VALIDATION_ERROR");
            }
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM p_performances", Integer.class))
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("없거나 삭제된 공연장은 404 VENUE_NOT_FOUND다")
        void rejectsMissingVenue() throws Exception {
            for (long venueId : new long[] {999, 303}) {
                expectError(registerDraft(draftBody(Map.of("venueId", venueId))), 404, "VENUE_NOT_FOUND");
            }
        }

        @Test
        @DisplayName("필수값 누락·열거값·길이·runningTime·posterUrl 형식·공백만은 400 VALIDATION_ERROR이고 저장하지 않는다")
        void rejectsInvalidBody() throws Exception {
            List<Map<String, Object>> invalid = List.of(
                    Map.of("venueId", NULL),
                    Map.of("title", NULL),
                    Map.of("title", "　"),
                    Map.of("title", "가".repeat(201)),
                    Map.of("genre", "OPERA"),
                    Map.of("genre", "musical"),
                    Map.of("ageRating", "18"),
                    Map.of("ageRating", "AGE_12"),
                    Map.of("runningTime", 0),
                    Map.of("runningTime", NULL),
                    Map.of("castInfo", "가".repeat(501)),
                    Map.of("posterUrl", "http://example.com/poster.jpg"),
                    Map.of("posterUrl", "https://" + "a".repeat(493)),
                    Map.of("posterUrl", "https://example.com/a b.jpg"),
                    Map.of("posterUrl", "example.com/poster.jpg"),
                    Map.of("posterUrl", "https://example.com/a b.jpg"),
                    Map.of("posterUrl", "https://"),
                    Map.of("title", " "));
            for (Map<String, Object> override : invalid) {
                expectError(registerDraft(draftBody(override)), 400, "VALIDATION_ERROR");
            }
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM p_performances", Integer.class))
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("SELLER가 아니면 403, 인증 헤더가 없으면 401이다")
        void requiresSeller() throws Exception {
            String body = draftBody(Map.of());
            for (String role : new String[] {"MASTER", "USER"}) {
                expectError(
                        mockMvc.perform(
                                as(1, role, post("/api/seller/performances")).content(body)),
                        403,
                        "FORBIDDEN");
            }
            expectError(
                    mockMvc.perform(post("/api/seller/performances")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)),
                    401,
                    "UNAUTHORIZED");
        }
    }

    @Nested
    @DisplayName("PUT /api/seller/performances/{performanceId}/grade-prices 등급별 가격 설정")
    class ReplaceGradePrices {

        @Test
        @DisplayName("가격 집합을 요청대로 바꾸고(변경·추가) 공연 version을 1 올린다")
        void replacesPrices() throws Exception {
            replacePrices(DRAFT, "\"2\"", prices(VIP, 160000, R, 90000, S, 40000))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.gradePrices[*].venueSeatGradeId", contains(301, 302, 303)))
                    .andExpect(jsonPath("$.data.gradePrices[*].gradeName", contains("VIP", "R", "S")))
                    .andExpect(jsonPath("$.data.gradePrices[*].price", contains(160000, 90000, 40000)))
                    .andExpect(jsonPath("$.data.performanceVersion").value(3));

            assertThat(livePrices(DRAFT)).containsExactly("301:160000", "302:90000", "303:40000");
            assertThat(performanceVersion(DRAFT)).isEqualTo(3);
        }

        @Test
        @DisplayName("빠진 등급의 가격은 논리 삭제하고, 응답 version을 다음 If-Match에 쓸 수 있다")
        void removesMissingGradeAndChainsVersion() throws Exception {
            replacePrices(DRAFT, "\"2\"", prices(VIP, 150000, R, 90000, S, 40000))
                    .andExpect(jsonPath("$.data.performanceVersion").value(3));
            replacePrices(DRAFT, "\"3\"", prices(R, 80000, VIP, 150000))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.gradePrices[*].venueSeatGradeId", contains(302, 301)))
                    .andExpect(jsonPath("$.data.performanceVersion").value(4));

            assertThat(livePrices(DRAFT)).containsExactly("301:150000", "302:80000");
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT deleted_by FROM p_performance_grade_prices"
                                    + " WHERE performance_id = ? AND venue_seat_grade_id = ? AND deleted_at IS NOT NULL"
                                    + " ORDER BY deleted_at DESC LIMIT 1",
                            String.class,
                            DRAFT,
                            S))
                    .isEqualTo("seller501");
        }

        @Test
        @DisplayName("삭제되지 않은 회차(취소 포함)는 구성 버전을 올리고 새 요청 ID로 커밋 뒤 다시 준비된다. 삭제된 회차는 그대로다")
        void requiresSeatSetupAgain() throws Exception {
            replacePrices(DRAFT, "\"2\"", prices(VIP, 160000, R, 90000)).andExpect(status().isOk());

            // 가짜 좌석 서비스가 성공으로 답하므로 커밋 뒤 첫 시도(attempts 1)로 READY가 된다
            Map<String, Object> ready = schedule(320);
            assertThat(ready)
                    .containsEntry("seat_config_version", 2L)
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_setup_attempts", 1)
                    .containsEntry("seat_setup_next_attempt_at", null);
            assertThat((String) ready.get("seat_setup_request_id"))
                    .isNotEqualTo("req-320")
                    .hasSize(36);

            Map<String, Object> failed = schedule(321);
            assertThat(failed)
                    .containsEntry("seat_config_version", 3L)
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_setup_error", null)
                    .containsEntry("seat_setup_attempts", 1);
            assertThat(failed.get("seat_setup_request_id")).isNotEqualTo(ready.get("seat_setup_request_id"));

            assertThat(schedule(322))
                    .containsEntry("seat_config_version", 1L)
                    .containsEntry("seat_setup_request_id", "req-322");

            // 취소 회차도 다시 준비하되 취소 상태·사유는 그대로다
            Map<String, Object> canceled = schedule(323);
            assertThat(canceled)
                    .containsEntry("seat_config_version", 2L)
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("operation_status", "CANCELED")
                    .containsEntry("cancel_reason", "공연장 사정")
                    // 재준비는 회차 명령이 아니라 회차 version을 올리지 않는다
                    .containsEntry("version", 1L);
            assertThat(canceled.get("seat_setup_request_id")).isNotEqualTo("req-323");

            // 재시도 대기 중이던 회차는 새 요청으로 바뀌어 다음 시각을 기다리지 않고 바로 보내고, 이전 오류를 비운다
            Map<String, Object> retrying = schedule(324);
            assertThat(retrying)
                    .containsEntry("seat_config_version", 2L)
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_setup_attempts", 1)
                    .containsEntry("seat_setup_next_attempt_at", null)
                    .containsEntry("seat_setup_error", null);
            assertThat(retrying.get("seat_setup_request_id")).isNotEqualTo("req-324");

            // 좌석 서비스에는 새 요청 ID·구성 버전과 바뀐 가격으로 보냈다
            assertThat(seatSetupClient.requests())
                    .extracting(request -> request.scheduleId() + ":" + request.configVersion() + ":"
                            + request.requestId()
                                    .equals(schedule(request.scheduleId()).get("seat_setup_request_id")))
                    .containsExactlyInAnyOrder("320:2:true", "321:3:true", "323:2:true", "324:2:true");
            assertThat(seatSetupClient.requests().get(0).seats())
                    .filteredOn(seat -> seat.venueSeatId() == 301)
                    .extracting(seat -> seat.basePrice())
                    .containsExactly(160000);
        }

        @Test
        @DisplayName("판매 대상 좌석이 쓰는 등급의 가격이 빠지면 400 GRADE_PRICE_MISSING이고, 모두 판매 제외된 등급(S)은 빼도 된다")
        void requiresPriceOfSellableGrades() throws Exception {
            for (String body : new String[] {prices(VIP, 150000), "{\"gradePrices\":[]}"}) {
                expectError(replacePrices(DRAFT, "\"2\"", body), 400, "GRADE_PRICE_MISSING");
            }
            assertThat(livePrices(DRAFT)).containsExactly("301:150000", "302:90000");
            assertThat(performanceVersion(DRAFT)).isEqualTo(2);
            assertThat(schedule(320)).containsEntry("seat_setup_status", "READY");

            replacePrices(DRAFT, "\"2\"", prices(VIP, 150000, R, 90000)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("삭제 좌석만 쓰는 등급은 가격을 빼도 되고, 판매 제외 행이 삭제된 좌석의 등급은 가격이 필요하다")
        void decidesSellableGradesByLiveRows() throws Exception {
            // 공연 314: VIP(311) 살아 있는 좌석, R(312) 삭제 좌석만, S(313) 판매 제외 행이 삭제됨
            expectError(replacePrices(314, "\"0\"", prices(311, 100000)), 400, "GRADE_PRICE_MISSING");
            replacePrices(314, "\"0\"", prices(311, 100000, 313, 50000))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.performanceVersion").value(1));
        }

        @Test
        @DisplayName("판매 대상 좌석이 없으면 빈 가격 집합도 성공한다")
        void acceptsEmptyPricesWithoutSellableSeats() throws Exception {
            replacePrices(315, "\"0\"", "{\"gradePrices\":[]}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.gradePrices").isEmpty())
                    .andExpect(jsonPath("$.data.performanceVersion").value(1));
        }

        @Test
        @DisplayName("다른 공연장 등급·삭제 등급·없는 등급은 400 VENUE_GRADE_MISMATCH다")
        void rejectsGradeOfOtherVenue() throws Exception {
            for (long gradeId : new long[] {305, 304, 9999}) {
                expectError(
                        replacePrices(DRAFT, "\"2\"", prices(VIP, 150000, R, 90000, gradeId, 10000)),
                        400,
                        "VENUE_GRADE_MISMATCH");
            }
            assertThat(livePrices(DRAFT)).containsExactly("301:150000", "302:90000");
        }

        @Test
        @DisplayName("요청 안 등급 중복·가격 0 이하·필수값 누락·정수가 아닌 등급 ID와 가격·int 범위 초과는 400 VALIDATION_ERROR다")
        void rejectsInvalidBody() throws Exception {
            for (String body : new String[] {
                prices(VIP, 150000, R, 90000, VIP, 160000),
                prices(VIP, 0, R, 90000),
                prices(VIP, -1, R, 90000),
                "{}",
                "{\"gradePrices\":[{\"venueSeatGradeId\":301}]}",
                "{\"gradePrices\":[{\"price\":1000}]}",
                "{\"gradePrices\":[{\"venueSeatGradeId\":301.9,\"price\":150000},{\"venueSeatGradeId\":302,\"price\":90000}]}",
                "{\"gradePrices\":[{\"venueSeatGradeId\":301,\"price\":1.9},{\"venueSeatGradeId\":302,\"price\":90000}]}",
                "{\"gradePrices\":[{\"venueSeatGradeId\":\"301\",\"price\":150000},{\"venueSeatGradeId\":302,\"price\":90000}]}",
                "{\"gradePrices\":[{\"venueSeatGradeId\":301,\"price\":\"150000\"},{\"venueSeatGradeId\":302,\"price\":90000}]}",
                "{\"gradePrices\":[{\"venueSeatGradeId\":301,\"price\":3000000000},{\"venueSeatGradeId\":302,\"price\":90000}]}"
            }) {
                expectError(replacePrices(DRAFT, "\"2\"", body), 400, "VALIDATION_ERROR");
            }
            assertThat(performanceVersion(DRAFT)).isEqualTo(2);
        }

        @Test
        @DisplayName("If-Match 누락 428, 불일치 409, 형식 오류 400이다")
        void checksIfMatch() throws Exception {
            String body = prices(VIP, 150000, R, 90000);
            expectError(replacePrices(DRAFT, null, body), 428, "PRECONDITION_REQUIRED");
            expectError(replacePrices(DRAFT, "\"1\"", body), 409, "VERSION_CONFLICT");
            expectError(replacePrices(DRAFT, "W/\"2\"", body), 400, "VALIDATION_ERROR");
            assertThat(performanceVersion(DRAFT)).isEqualTo(2);
        }

        @Test
        @DisplayName("없거나 삭제된 공연 404, 다른 판매자 공연 403, 공개된 공연 409 PERFORMANCE_CONFIGURATION_LOCKED다")
        void checksPerformance() throws Exception {
            String body = prices(VIP, 150000, R, 90000);
            for (long id : new long[] {999, DELETED}) {
                expectError(replacePrices(id, "\"0\"", body), 404, "PERFORMANCE_NOT_FOUND");
            }
            expectError(replacePrices(OTHER_SELLERS, "\"0\"", body), 403, "FORBIDDEN");
            expectError(replacePrices(PUBLISHED, "\"3\"", body), 409, "PERFORMANCE_CONFIGURATION_LOCKED");
            assertThat(performanceVersion(PUBLISHED)).isEqualTo(3);
        }

        @Test
        @DisplayName("오류가 겹치면 소유 → version → 공개 여부 → 등급 소속 → 가격 누락 순서로 판정한다")
        void checksErrorOrder() throws Exception {
            // 다른 판매자 공연 + 옛 version → FORBIDDEN
            expectError(replacePrices(OTHER_SELLERS, "\"9\"", prices(VIP, 150000)), 403, "FORBIDDEN");
            // 공개 공연 + 옛 version → VERSION_CONFLICT
            expectError(replacePrices(PUBLISHED, "\"9\"", prices(VIP, 150000)), 409, "VERSION_CONFLICT");
            // 공개 공연 + 다른 공연장 등급 → PERFORMANCE_CONFIGURATION_LOCKED
            expectError(replacePrices(PUBLISHED, "\"3\"", prices(305, 10000)), 409, "PERFORMANCE_CONFIGURATION_LOCKED");
            // 다른 공연장 등급 + 가격 누락 → VENUE_GRADE_MISMATCH
            expectError(replacePrices(DRAFT, "\"2\"", prices(305, 10000)), 400, "VENUE_GRADE_MISMATCH");
        }

        @Test
        @DisplayName("SELLER가 아니면 403, Path ID가 숫자가 아니거나 0 이하면 400이다")
        void rejectsRoleAndPath() throws Exception {
            String body = prices(VIP, 150000, R, 90000);
            expectError(
                    mockMvc.perform(as(1, "MASTER", put("/api/seller/performances/310/grade-prices"))
                            .header("If-Match", "\"2\"")
                            .content(body)),
                    403,
                    "FORBIDDEN");
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(replacePrices(id, "\"2\"", body), 400, "VALIDATION_ERROR");
            }
        }
    }
}
