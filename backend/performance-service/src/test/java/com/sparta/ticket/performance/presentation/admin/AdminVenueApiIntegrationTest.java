package com.sparta.ticket.performance.presentation.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.port.VenueStore;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.venue.VenueSeat;
import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.List;
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
 * 관리자 공연장 배치 등록(공연장·좌석 등급·물리 좌석)을 독립 fixture와 실제 PostgreSQL로 검증한다.
 * fixture 공연장 201은 version 3, 등급 VIP(201)·R(202)·삭제 등급(203), 좌석 A-1-1(201)·삭제 좌석 A-1-2를 가진다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/admin-venue-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class AdminVenueApiIntegrationTest extends PerformanceIntegrationTest {

    private static final long EDITABLE = 201;
    private static final long IN_USE = 202;
    private static final long USED_BY_DELETED_PERFORMANCE = 203;
    private static final long DELETED = 204;
    private static final long OTHER = 205;
    private static final long MISSING = 999;
    private static final String DATE_TIME_KST = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+09:00";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "admin01")
                .header(UserHeaders.ROLE, role)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions createVenue(String body) throws Exception {
        return mockMvc.perform(as("MASTER", post("/api/admin/venues")).content(body));
    }

    private ResultActions registerGrades(Object venueId, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = as("MASTER", post("/api/admin/venues/" + venueId + "/seat-grades"));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request.content(body));
    }

    private ResultActions registerSeats(Object venueId, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = as("MASTER", post("/api/admin/venues/" + venueId + "/seats"));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request.content(body));
    }

    private static String grades(String... names) {
        StringBuilder json = new StringBuilder("{\"grades\":[");
        for (int i = 0; i < names.length; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"name\":\"")
                    .append(names[i])
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    /** 좌석 하나의 JSON. 여러 개는 seats(...)로 묶는다. */
    private static String seat(String section, String rowLabel, int seatNumber, long gradeId) {
        return "{\"section\":\"%s\",\"rowLabel\":\"%s\",\"seatNumber\":%d,\"venueSeatGradeId\":%d}"
                .formatted(section, rowLabel, seatNumber, gradeId);
    }

    private static String seats(String... seats) {
        return "{\"seats\":[" + String.join(",", seats) + "]}";
    }

    private long venueVersion(long venueId) {
        return jdbcTemplate.queryForObject("SELECT version FROM p_venues WHERE id = ?", Long.class, venueId);
    }

    private int liveGradeCount(long venueId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM p_venue_seat_grades WHERE venue_id = ? AND deleted_at IS NULL",
                Integer.class,
                venueId);
    }

    private int liveSeatCount(long venueId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM p_venue_seats WHERE venue_id = ? AND deleted_at IS NULL", Integer.class, venueId);
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Nested
    @DisplayName("POST /api/admin/venues 공연장 등록")
    class RegisterVenue {

        @Test
        @DisplayName("201과 Location을 주고 앞뒤 공백을 지워 version 0으로 저장한다")
        void createsVenue() throws Exception {
            MvcResult result = createVenue("{\"name\":\" 서울 공연장 \",\"address\":\"서울특별시 중구 예시로 10\",\"region\":\"서울\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.name").value("서울 공연장"))
                    .andExpect(jsonPath("$.data.address").value("서울특별시 중구 예시로 10"))
                    .andExpect(jsonPath("$.data.region").value("서울"))
                    .andExpect(jsonPath("$.data.version").value(0))
                    .andExpect(jsonPath("$.data.createdAt", matchesPattern(DATE_TIME_KST)))
                    .andExpect(jsonPath("$.data.updatedAt", matchesPattern(DATE_TIME_KST)))
                    .andReturn();

            long id = objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .at("/data/id")
                    .asLong();
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/seller/venues/" + id);
            assertThat(jdbcTemplate.queryForObject("SELECT created_by FROM p_venues WHERE id = ?", String.class, id))
                    .isEqualTo("admin01");
        }

        @Test
        @DisplayName("알 수 없는 필드는 무시한다")
        void ignoresUnknownFields() throws Exception {
            createVenue("{\"name\":\"공연장\",\"address\":\"주소\",\"region\":\"서울\",\"capacity\":100}")
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("필수값 누락·공백만·길이 초과는 400 VALIDATION_ERROR다")
        void rejectsInvalidBody() throws Exception {
            for (String body : new String[] {
                "{\"address\":\"주소\",\"region\":\"서울\"}",
                "{\"name\":\"   \",\"address\":\"주소\",\"region\":\"서울\"}",
                "{\"name\":\"　\",\"address\":\"주소\",\"region\":\"서울\"}",
                "{\"name\":\"공연장\",\"address\":\"　　\",\"region\":\"서울\"}",
                "{\"name\":\"공연장\",\"address\":\"주소\",\"region\":\"" + "가".repeat(21) + "\"}",
                "{\"name\":\"" + "가".repeat(101) + "\",\"address\":\"주소\",\"region\":\"서울\"}"
            }) {
                expectError(createVenue(body), 400, "VALIDATION_ERROR");
            }
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM p_venues", Integer.class))
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("MASTER가 아니면 403, 인증 헤더가 없으면 401이다")
        void requiresMaster() throws Exception {
            String body = "{\"name\":\"공연장\",\"address\":\"주소\",\"region\":\"서울\"}";
            for (String role : new String[] {"SELLER", "USER"}) {
                expectError(mockMvc.perform(as(role, post("/api/admin/venues")).content(body)), 403, "FORBIDDEN");
            }
            expectError(
                    mockMvc.perform(post("/api/admin/venues")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)),
                    401,
                    "UNAUTHORIZED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM p_venues", Integer.class))
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("POST /api/admin/venues/{venueId}/seat-grades 좌석 등급 일괄 등록")
    class RegisterGrades {

        @Test
        @DisplayName("요청 순서대로 등급을 만들고 공연장 version을 1 올린다")
        void createsGradesAndIncreasesVersion() throws Exception {
            registerGrades(EDITABLE, "\"3\"", grades(" S ", "A"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.grades", hasSize(2)))
                    .andExpect(jsonPath("$.data.grades[*].name", contains("S", "A")))
                    .andExpect(jsonPath("$.data.grades[0].id").isNumber())
                    .andExpect(jsonPath("$.data.venueVersion").value(4));

            assertThat(venueVersion(EDITABLE)).isEqualTo(4);
            assertThat(liveGradeCount(EDITABLE)).isEqualTo(4);
        }

        @Test
        @DisplayName("삭제된 등급과 같은 이름은 다시 쓸 수 있다")
        void reusesDeletedGradeName() throws Exception {
            registerGrades(EDITABLE, "\"3\"", grades("삭제 등급")).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("응답의 venueVersion을 다음 If-Match에 쓰고, 옛 version은 409다")
        void chainsVersion() throws Exception {
            registerGrades(EDITABLE, "\"3\"", grades("S"))
                    .andExpect(jsonPath("$.data.venueVersion").value(4));
            registerGrades(EDITABLE, "\"4\"", grades("A"))
                    .andExpect(jsonPath("$.data.venueVersion").value(5));
            expectError(registerGrades(EDITABLE, "\"4\"", grades("B")), 409, "VERSION_CONFLICT");
            assertThat(venueVersion(EDITABLE)).isEqualTo(5);
        }

        @Test
        @DisplayName("If-Match가 없으면 428, 현재 version과 다르면 409, 형식이 틀리면 400이다")
        void checksIfMatch() throws Exception {
            expectError(registerGrades(EDITABLE, null, grades("S")), 428, "PRECONDITION_REQUIRED");
            expectError(registerGrades(EDITABLE, "\"2\"", grades("S")), 409, "VERSION_CONFLICT");
            expectError(registerGrades(EDITABLE, "W/\"3\"", grades("S")), 400, "VALIDATION_ERROR");
            assertThat(venueVersion(EDITABLE)).isEqualTo(3);
            assertThat(liveGradeCount(EDITABLE)).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 공연장에 같은 이름이 있으면 409 DUPLICATE_VENUE_GRADE이고 하나도 저장하지 않는다")
        void rejectsExistingName() throws Exception {
            expectError(registerGrades(EDITABLE, "\"3\"", grades("S", "VIP")), 409, "DUPLICATE_VENUE_GRADE");
            assertThat(liveGradeCount(EDITABLE)).isEqualTo(2);
            assertThat(venueVersion(EDITABLE)).isEqualTo(3);
        }

        @Test
        @DisplayName("다른 공연장에 있는 이름은 중복이 아니다")
        void allowsNameOfOtherVenue() throws Exception {
            // 공연장 201에 있는 R을 공연장 205에 등록한다
            registerGrades(OTHER, "\"0\"", grades("R")).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("요청 안 이름 중복(공백 정리 후 포함)·빈 목록·공백만·길이 초과는 400 VALIDATION_ERROR다")
        void rejectsInvalidBody() throws Exception {
            for (String body : new String[] {
                grades("S", "S"),
                grades("S", " S"),
                "{\"grades\":[]}",
                "{}",
                grades("  "),
                grades("　"),
                grades("가".repeat(21))
            }) {
                expectError(registerGrades(EDITABLE, "\"3\"", body), 400, "VALIDATION_ERROR");
            }
            assertThat(liveGradeCount(EDITABLE)).isEqualTo(2);
        }

        @Test
        @DisplayName("없거나 삭제된 공연장은 404 VENUE_NOT_FOUND다")
        void rejectsMissingVenue() throws Exception {
            for (long id : new long[] {MISSING, DELETED}) {
                expectError(registerGrades(id, "\"0\"", grades("S")), 404, "VENUE_NOT_FOUND");
            }
        }

        @Test
        @DisplayName("공연이 참조한 공연장은 삭제된 공연이라도 409 VENUE_LAYOUT_IN_USE다")
        void rejectsVenueInUse() throws Exception {
            for (long id : new long[] {IN_USE, USED_BY_DELETED_PERFORMANCE}) {
                expectError(registerGrades(id, "\"0\"", grades("S")), 409, "VENUE_LAYOUT_IN_USE");
                assertThat(venueVersion(id)).isZero();
            }
        }

        @Test
        @DisplayName("MASTER가 아니면 403, Path ID가 숫자가 아니거나 0 이하면 400이다")
        void rejectsRoleAndPath() throws Exception {
            expectError(
                    mockMvc.perform(as("SELLER", post("/api/admin/venues/201/seat-grades"))
                            .header("If-Match", "\"3\"")
                            .content(grades("S"))),
                    403,
                    "FORBIDDEN");
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(registerGrades(id, "\"3\"", grades("S")), 400, "VALIDATION_ERROR");
            }
        }
    }

    @Nested
    @DisplayName("POST /api/admin/venues/{venueId}/seats 물리 좌석 일괄 등록")
    class RegisterSeats {

        @Test
        @DisplayName("요청 순서대로 좌석을 만들고 createdCount·createdSeatIds·venueVersion을 준다")
        void createsSeatsAndIncreasesVersion() throws Exception {
            MvcResult result = registerSeats(
                            EDITABLE, "\"3\"", seats(seat(" B ", " 1 ", 1, 201), seat("B", "1", 2, 202)))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.data.createdCount").value(2))
                    .andExpect(jsonPath("$.data.createdSeatIds", hasSize(2)))
                    .andExpect(jsonPath("$.data.venueVersion").value(4))
                    .andReturn();

            JsonNode ids = objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .at("/data/createdSeatIds");
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT section || '-' || row_label || '-' || seat_number || ':' || venue_seat_grade_id"
                                    + " FROM p_venue_seats WHERE id = ?",
                            String.class,
                            ids.get(0).asLong()))
                    .isEqualTo("B-1-1:201");
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(3);
            assertThat(venueVersion(EDITABLE)).isEqualTo(4);
        }

        @Test
        @DisplayName("삭제된 좌석과 같은 위치는 다시 쓸 수 있다")
        void reusesDeletedSeatPosition() throws Exception {
            registerSeats(EDITABLE, "\"3\"", seats(seat("A", "1", 2, 201))).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("기존 좌석과 위치가 겹치면(공백 정리 후 포함) 409 DUPLICATE_VENUE_SEAT이고 전체를 롤백한다")
        void rejectsExistingPositionAndRollsBack() throws Exception {
            for (String duplicate : new String[] {seat("A", "1", 1, 202), seat(" A ", "1", 1, 202)}) {
                expectError(
                        registerSeats(EDITABLE, "\"3\"", seats(seat("C", "1", 1, 201), duplicate)),
                        409,
                        "DUPLICATE_VENUE_SEAT");
            }
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
            assertThat(venueVersion(EDITABLE)).isEqualTo(3);
        }

        @Test
        @DisplayName("요청 안에서 위치가 겹치면 409 DUPLICATE_VENUE_SEAT이다")
        void rejectsDuplicateInRequest() throws Exception {
            expectError(
                    registerSeats(EDITABLE, "\"3\"", seats(seat("C", "1", 1, 201), seat("C", "1", 1, 202))),
                    409,
                    "DUPLICATE_VENUE_SEAT");
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
        }

        @Test
        @DisplayName("다른 공연장 등급·삭제 등급·없는 등급은 400 VENUE_GRADE_MISMATCH다")
        void rejectsGradeOfOtherVenue() throws Exception {
            for (long gradeId : new long[] {205, 203, 9999}) {
                expectError(
                        registerSeats(EDITABLE, "\"3\"", seats(seat("C", "1", 1, 201), seat("C", "1", 2, gradeId))),
                        400,
                        "VENUE_GRADE_MISMATCH");
            }
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
        }

        @Test
        @DisplayName("빈 목록·좌석 번호 0·필수값 누락·길이 초과·정수가 아닌 번호와 등급 ID는 400 VALIDATION_ERROR다")
        void rejectsInvalidBody() throws Exception {
            for (String body : new String[] {
                "{\"seats\":[]}",
                seats(seat("C", "1", 0, 201)),
                "{\"seats\":[{\"section\":\"C\",\"rowLabel\":\"1\",\"seatNumber\":1}]}",
                seats(seat("가".repeat(21), "1", 1, 201)),
                seats(seat("C", "가".repeat(11), 1, 201)),
                seats(seat("  ", "1", 1, 201)),
                seats(seat("　", "1", 1, 201)),
                seats(seat("C", "　", 1, 201)),
                // 서비스 전체 숫자 규칙(이슈 #181): 소수를 잘라 다른 좌석 번호·등급으로 저장하지 않는다
                "{\"seats\":[{\"section\":\"C\",\"rowLabel\":\"1\",\"seatNumber\":1.5,\"venueSeatGradeId\":201}]}",
                "{\"seats\":[{\"section\":\"C\",\"rowLabel\":\"1\",\"seatNumber\":1,\"venueSeatGradeId\":201.9}]}",
                "{\"seats\":[{\"section\":\"C\",\"rowLabel\":\"1\",\"seatNumber\":1,\"venueSeatGradeId\":\"201\"}]}"
            }) {
                expectError(registerSeats(EDITABLE, "\"3\"", body), 400, "VALIDATION_ERROR");
            }
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
        }

        @Test
        @DisplayName("If-Match 누락 428, 불일치 409, 없거나 삭제된 공연장 404, 삭제된 공연 참조 포함 사용 중 공연장 409다")
        void checksPreconditions() throws Exception {
            String body = seats(seat("C", "1", 1, 201));
            expectError(registerSeats(EDITABLE, null, body), 428, "PRECONDITION_REQUIRED");
            expectError(registerSeats(EDITABLE, "\"4\"", body), 409, "VERSION_CONFLICT");
            for (long id : new long[] {MISSING, DELETED}) {
                expectError(registerSeats(id, "\"0\"", body), 404, "VENUE_NOT_FOUND");
            }
            for (long id : new long[] {IN_USE, USED_BY_DELETED_PERFORMANCE}) {
                expectError(registerSeats(id, "\"0\"", body), 409, "VENUE_LAYOUT_IN_USE");
                assertThat(venueVersion(id)).isZero();
            }
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
        }

        @Test
        @DisplayName("MASTER가 아니면 403, 인증 헤더가 없으면 401, Path ID가 숫자가 아니거나 0 이하면 400이다")
        void rejectsRoleAndPath() throws Exception {
            String body = seats(seat("C", "1", 1, 201));
            for (String role : new String[] {"SELLER", "USER"}) {
                expectError(
                        mockMvc.perform(as(role, post("/api/admin/venues/201/seats"))
                                .header("If-Match", "\"3\"")
                                .content(body)),
                        403,
                        "FORBIDDEN");
            }
            expectError(
                    mockMvc.perform(post("/api/admin/venues/201/seats")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("If-Match", "\"3\"")
                            .content(body)),
                    401,
                    "UNAUTHORIZED");
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(registerSeats(id, "\"3\"", body), 400, "VALIDATION_ERROR");
            }
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
        }

        @Test
        @DisplayName("오류가 겹치면 version → 사용 여부 → 요청 안 중복 → 등급 소속 → 기존 위치 중복 순서로 판정한다")
        void checksErrorOrder() throws Exception {
            // 사용 중 공연장 + 옛 version → VERSION_CONFLICT
            expectError(registerSeats(IN_USE, "\"9\"", seats(seat("C", "1", 1, 201))), 409, "VERSION_CONFLICT");
            // 사용 중 공연장 + 요청 안 중복 → VENUE_LAYOUT_IN_USE
            expectError(
                    registerSeats(IN_USE, "\"0\"", seats(seat("C", "1", 1, 201), seat("C", "1", 1, 201))),
                    409,
                    "VENUE_LAYOUT_IN_USE");
            // 요청 안 중복 + 없는 등급 → DUPLICATE_VENUE_SEAT
            expectError(
                    registerSeats(EDITABLE, "\"3\"", seats(seat("C", "1", 1, 9999), seat("C", "1", 1, 9999))),
                    409,
                    "DUPLICATE_VENUE_SEAT");
            // 없는 등급 + 기존 위치 중복(A-1-1) → VENUE_GRADE_MISMATCH
            expectError(registerSeats(EDITABLE, "\"3\"", seats(seat("A", "1", 1, 9999))), 400, "VENUE_GRADE_MISMATCH");
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
            assertThat(venueVersion(EDITABLE)).isEqualTo(3);
        }

        @Test
        @DisplayName("공연장 → 등급 → 좌석 순서로 이어서 등록할 수 있다")
        void registersWholeLayout() throws Exception {
            MvcResult venue = createVenue("{\"name\":\"새 공연장\",\"address\":\"주소\",\"region\":\"서울\"}")
                    .andReturn();
            long venueId = objectMapper
                    .readTree(venue.getResponse().getContentAsString())
                    .at("/data/id")
                    .asLong();
            MvcResult grade = registerGrades(venueId, "\"0\"", grades("VIP"))
                    .andExpect(jsonPath("$.data.venueVersion").value(1))
                    .andReturn();
            long gradeId = objectMapper
                    .readTree(grade.getResponse().getContentAsString())
                    .at("/data/grades/0/id")
                    .asLong();

            registerSeats(venueId, "\"1\"", seats(seat("A", "1", 1, gradeId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.venueVersion").value(2));
            assertThat(venueVersion(venueId)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("부분 UNIQUE 위반 안전망")
    class UniqueViolation {

        @Autowired
        private VenueStore store;

        /** 서비스의 사전 확인을 거치지 않고 저장소에 바로 넣어, 동시 요청이 사전 확인을 지나친 경우를 흉내 낸다. */
        @Test
        @DisplayName("등급 이름이 겹치면 DB 제약 이름으로 판별해 409 DUPLICATE_VENUE_GRADE로 바꾼다")
        void translatesGradeNameViolation() {
            assertThatThrownBy(() -> store.saveGrades(List.of(VenueSeatGrade.create(EDITABLE, "VIP"))))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(PerformanceErrorCode.DUPLICATE_VENUE_GRADE));
            assertThat(liveGradeCount(EDITABLE)).isEqualTo(2);
        }

        @Test
        @DisplayName("좌석 위치가 겹치면 DB 제약 이름으로 판별해 409 DUPLICATE_VENUE_SEAT로 바꾼다")
        void translatesSeatPositionViolation() {
            assertThatThrownBy(() -> store.saveSeats(List.of(VenueSeat.create(EDITABLE, 201L, "A", "1", 1))))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(PerformanceErrorCode.DUPLICATE_VENUE_SEAT));
            assertThat(liveSeatCount(EDITABLE)).isEqualTo(1);
        }
    }
}
