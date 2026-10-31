package com.sparta.ticket.performance.presentation.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
 * 회차 판매 중지·재개(PUT /api/admin/schedules/{scheduleId}/sales-suspension)를 실제 PostgreSQL로 검증한다.
 * fixture는 sql/admin-schedule-fixture.sql이다. 회차 920은 판매 중(version 0), 921은 '좌석 점검'으로 중지됨(version 3)이다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/admin-schedule-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class AdminScheduleApiIntegrationTest extends PerformanceIntegrationTest {

    private static final long ON_SALE = 920;
    private static final long SUSPENDED = 921;
    private static final long CANCELED = 922;
    private static final long DELETED = 923;
    private static final long CANCELED_PERFORMANCE = 924;
    private static final long BEFORE_OPEN_SUSPENDED = 925;
    private static final long DRAFT_PERFORMANCE = 926;
    private static final long DELETED_PERFORMANCE = 927;

    private static final String RESUME = "{\"suspended\":false}";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String suspend(String reason) {
        return "{\"suspended\":true,\"reason\":\"" + reason + "\"}";
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "admin")
                .header(UserHeaders.ROLE, role)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions change(String role, Object scheduleId, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = as(
                        role, put("/api/admin/schedules/" + scheduleId + "/sales-suspension"))
                .content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request);
    }

    private ResultActions change(Object scheduleId, String ifMatch, String body) throws Exception {
        return change("MASTER", scheduleId, ifMatch, body);
    }

    private Map<String, Object> schedule(long scheduleId) {
        return jdbcTemplate.queryForMap(
                "SELECT sales_suspended, suspension_reason, version FROM p_schedules WHERE id = ?", scheduleId);
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Nested
    @DisplayName("중지·재개")
    class Change {

        @Test
        @DisplayName("중지하면 P15를 주고 version이 1 오른다. 사유는 앞뒤 공백을 지워 저장하고, 공개 조회는 UNAVAILABLE, 판매 검증은 SALES_SUSPENDED다")
        void suspends() throws Exception {
            mockMvc.perform(get("/api/schedules/" + ON_SALE))
                    .andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"));

            change(ON_SALE, "\"0\"", suspend("  공연장 점검  "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.scheduleId").value(ON_SALE))
                    .andExpect(jsonPath("$.data.salesSuspended").value(true))
                    .andExpect(jsonPath("$.data.suspensionReason").value("공연장 점검"))
                    .andExpect(jsonPath("$.data.version").value(1));

            assertThat(schedule(ON_SALE))
                    .containsEntry("sales_suspended", true)
                    .containsEntry("suspension_reason", "공연장 점검")
                    .containsEntry("version", 1L);
            // 부모 공연 version은 바뀌지 않는다
            assertThat(jdbcTemplate.queryForObject("SELECT version FROM p_performances WHERE id = 910", Long.class))
                    .isEqualTo(2L);

            mockMvc.perform(get("/api/schedules/" + ON_SALE))
                    .andExpect(jsonPath("$.data.salesSuspended").value(true))
                    .andExpect(jsonPath("$.data.salesStatus").value("UNAVAILABLE"));
            mockMvc.perform(post("/internal/schedules/" + ON_SALE + "/sale-validations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"configVersion\":1,\"venueSeatIds\":[901]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowed").value(false))
                    .andExpect(jsonPath("$.reasonCode").value("SALES_SUSPENDED"));
        }

        @Test
        @DisplayName("재개하면 사유를 지우고 다시 판매한다")
        void resumes() throws Exception {
            change(SUSPENDED, "\"3\"", RESUME)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.salesSuspended").value(false))
                    .andExpect(jsonPath("$.data.suspensionReason").value(nullValue()))
                    .andExpect(jsonPath("$.data.version").value(4));

            assertThat(schedule(SUSPENDED))
                    .containsEntry("sales_suspended", false)
                    .containsEntry("suspension_reason", null);
            mockMvc.perform(get("/api/schedules/" + SUSPENDED))
                    .andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"));

            // reason을 null로 보내도 재개다
            change(ON_SALE, "\"0\"", "{\"suspended\":false,\"reason\":null}").andExpect(status().isOk());
        }

        @Test
        @DisplayName("재개해도 오픈 전 조건은 그대로라 BEFORE_OPEN이다")
        void resumeKeepsOtherConditions() throws Exception {
            change(BEFORE_OPEN_SUSPENDED, "\"1\"", RESUME).andExpect(status().isOk());

            mockMvc.perform(get("/api/schedules/" + BEFORE_OPEN_SUSPENDED))
                    .andExpect(jsonPath("$.data.salesSuspended").value(false))
                    .andExpect(jsonPath("$.data.salesStatus").value("BEFORE_OPEN"));
        }

        @Test
        @DisplayName("결과가 지금과 같으면 바꾸지 않고 version도 그대로다. 중지 중에 다른 사유로 중지하면 사유만 바꾸고 version이 오른다")
        void sameResultKeepsVersion() throws Exception {
            change(SUSPENDED, "\"3\"", suspend(" 좌석 점검 "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.suspensionReason").value("좌석 점검"))
                    .andExpect(jsonPath("$.data.version").value(3));
            change(ON_SALE, "\"0\"", RESUME)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.salesSuspended").value(false))
                    .andExpect(jsonPath("$.data.version").value(0));
            assertThat(schedule(SUSPENDED)).containsEntry("version", 3L);
            assertThat(schedule(ON_SALE)).containsEntry("version", 0L);

            change(SUSPENDED, "\"3\"", suspend("음향 점검"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.suspensionReason").value("음향 점검"))
                    .andExpect(jsonPath("$.data.version").value(4));
        }

        @Test
        @DisplayName("초안 공연의 회차도 중지할 수 있다")
        void suspendsDraftPerformanceSchedule() throws Exception {
            change(DRAFT_PERFORMANCE, "\"0\"", suspend("공개 전 점검"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.salesSuspended").value(true));
        }
    }

    @Nested
    @DisplayName("오류")
    class Errors {

        @Test
        @DisplayName("중지에 사유가 없거나 공백뿐이거나 500자를 넘으면, 재개에 사유를 보내면, suspended가 없으면 400")
        void rejectsInvalidBody() throws Exception {
            String[] bodies = {
                "{\"suspended\":true}",
                "{\"suspended\":true,\"reason\":null}",
                suspend("   "),
                suspend("　"),
                suspend("가".repeat(501)),
                "{\"suspended\":false,\"reason\":\"재개\"}",
                "{\"suspended\":false,\"reason\":\"\"}",
                "{\"reason\":\"점검\"}",
                "{\"suspended\":\"yes\"}"
            };
            for (String body : bodies) {
                expectError(change(ON_SALE, "\"0\"", body), 400, "VALIDATION_ERROR");
            }
            // 앞뒤 공백을 지운 뒤 500자는 된다
            change(ON_SALE, "\"0\"", suspend(" " + "가".repeat(500) + " ")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("없거나 삭제된 회차, 삭제된 공연의 회차는 404, 취소된 회차와 취소된 공연의 회차는 409 INVALID_STATUS_TRANSITION")
        void rejectsBySchedule() throws Exception {
            expectError(change(9999, "\"0\"", RESUME), 404, "SCHEDULE_NOT_FOUND");
            expectError(change(DELETED, "\"0\"", RESUME), 404, "SCHEDULE_NOT_FOUND");
            expectError(change(DELETED_PERFORMANCE, "\"0\"", RESUME), 404, "SCHEDULE_NOT_FOUND");
            expectError(change(CANCELED, "\"1\"", suspend("점검")), 409, "INVALID_STATUS_TRANSITION");
            expectError(change(CANCELED_PERFORMANCE, "\"0\"", suspend("점검")), 409, "INVALID_STATUS_TRANSITION");
            // 취소를 버전보다 먼저 본다
            expectError(change(CANCELED, "\"0\"", suspend("점검")), 409, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("If-Match가 없으면 428, 다르면 409 VERSION_CONFLICT다. 결과가 같은 요청도 version을 확인한다")
        void rejectsByIfMatch() throws Exception {
            expectError(change(ON_SALE, null, suspend("점검")), 428, "PRECONDITION_REQUIRED");
            expectError(change(ON_SALE, "\"5\"", suspend("점검")), 409, "VERSION_CONFLICT");
            expectError(change(ON_SALE, "\"5\"", RESUME), 409, "VERSION_CONFLICT");
            assertThat(schedule(ON_SALE))
                    .containsEntry("sales_suspended", false)
                    .containsEntry("version", 0L);
        }

        @Test
        @DisplayName("MASTER가 아니면 403, 회차 ID가 숫자가 아니거나 0 이하면 400")
        void rejectsByRoleAndPath() throws Exception {
            for (String role : new String[] {"SELLER", "USER"}) {
                expectError(change(role, ON_SALE, "\"0\"", suspend("점검")), 403, "FORBIDDEN");
            }
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(change(id, "\"0\"", suspend("점검")), 400, "VALIDATION_ERROR");
            }
        }
    }

    /**
     * 판매 중지가 회차 행을 FOR UPDATE로 잠그고 나서 회차를 읽는지 확인한다. 판매 중지는 Entity로 모든 컬럼을 다시 쓰고, 좌석 준비는
     * version을 올리지 않는 JPQL UPDATE로 저장하므로, 잠금이 빠지면 @Version으로도 좌석 준비 결과의 덮어쓰기를 잡지 못한다.
     * 테스트가 별도 연결로 회차 행을 먼저 잠그고 → 판매 중지가 회차 조회(SELECT … FOR UPDATE)에서 기다리는 것을 확인하고 →
     * 그 연결에서 좌석 준비 결과를 커밋해 잠금을 푼 뒤 → 두 변경이 모두 남는지 본다.
     * Note: 기다리는 쿼리까지 확인한다. 잠금 조회가 빠지면 나중의 UPDATE가 대신 기다려 대기 여부만으로는 잘못 통과한다.
     */
    @Nested
    @DisplayName("회차 행 잠금")
    class RowLock {

        private static final long WAIT_MILLIS = 10_000;

        @Autowired
        private DataSource dataSource;

        /** holderPid가 막고 있는 다른 연결의 현재 쿼리. 기다리는 연결이 없으면 null. */
        private String blockedQuery(int holderPid) {
            return jdbcTemplate
                    .queryForList(
                            "SELECT query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                            String.class,
                            holderPid)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }

        @Test
        @DisplayName("좌석 준비가 회차를 잠그고 결과를 커밋하는 동안 판매 중지는 기다리고, 끝난 뒤 두 변경이 모두 남는다. version은 판매 중지분 1만 오른다")
        void waitsForSeatSetup() throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<MvcResult> suspending = null;
            MvcResult result;
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try (Statement statement = holder.createStatement()) {
                    int holderPid;
                    try (ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
                        pid.next();
                        holderPid = pid.getInt(1);
                    }
                    statement.execute("SELECT id FROM p_schedules WHERE id = " + ON_SALE + " FOR UPDATE");

                    suspending = executor.submit(
                            () -> change(ON_SALE, "\"0\"", suspend("공연장 점검")).andReturn());

                    String waiting = null;
                    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
                    while (waiting == null && !suspending.isDone() && System.currentTimeMillis() < deadline) {
                        waiting = blockedQuery(holderPid);
                        if (waiting == null) {
                            Thread.sleep(20);
                        }
                    }
                    assertThat(waiting).as("판매 중지가 회차 행 잠금을 기다려야 한다").isNotNull();
                    // Hibernate는 PostgreSQL에서 PESSIMISTIC_WRITE를 FOR NO KEY UPDATE로 보낸다
                    assertThat(waiting.toLowerCase())
                            .as("기다리는 쿼리는 회차 조회의 SELECT … FOR (NO KEY) UPDATE여야 한다")
                            .startsWith("select")
                            .contains("from p_schedules")
                            .containsPattern("for (no key )?update");
                    assertThat(suspending.isDone()).isFalse();

                    // 좌석 준비 결과 반영과 같은 방식: 좌석 준비 필드만 바꾸고 version은 올리지 않는다
                    statement.executeUpdate("UPDATE p_schedules SET seat_setup_status = 'FAILED',"
                            + " seat_setup_error = '좌석 준비 실패', seat_setup_request_id = 'req-920-2',"
                            + " seat_config_version = 2 WHERE id = " + ON_SALE);
                    holder.commit();
                } catch (Exception | AssertionError e) {
                    holder.rollback();
                    awaitQuietly(suspending);
                    throw e;
                }
                // 커밋은 기다리던 요청을 깨울 뿐이다. 응답을 받은 뒤에 executor를 끝내야 처리 중인 요청을 중단시키지 않는다
                result = suspending.get(30, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) JsonPath.read(result.getResponse().getContentAsString(), "$.data.version"))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForMap(
                            "SELECT sales_suspended, suspension_reason, seat_setup_status, seat_setup_error,"
                                    + " seat_setup_request_id, seat_config_version, version FROM p_schedules WHERE id = ?",
                            ON_SALE))
                    .containsEntry("sales_suspended", true)
                    .containsEntry("suspension_reason", "공연장 점검")
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_error", "좌석 준비 실패")
                    .containsEntry("seat_setup_request_id", "req-920-2")
                    .containsEntry("seat_config_version", 2L)
                    .containsEntry("version", 1L);
        }

        /** 실패로 끝날 때 판매 중지 요청이 끝나기를 기다린다. 남겨 두면 다음 테스트의 TRUNCATE와 교착한다. */
        private void awaitQuietly(Future<MvcResult> suspending) {
            if (suspending == null) {
                return;
            }
            try {
                suspending.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 원래 실패를 그대로 알린다
            }
        }
    }
}
