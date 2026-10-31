package com.sparta.ticket.performance.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
 * 공연 공개(POST /api/seller/performances/{performanceId}/publication)를 실제 PostgreSQL로 검증한다.
 * fixture는 sql/seller-publication-fixture.sql이다. 공연 710은 판매자 801의 초안(version 1)이고 공개 조건을 모두 갖췄다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/seller-publication-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SellerPerformancePublicationApiIntegrationTest extends PerformanceIntegrationTest {

    private static final long SELLER = 801;
    private static final long DRAFT = 710;
    private static final long PUBLISHED = 711;
    private static final long OTHER_SELLERS = 712;
    private static final long DELETED = 713;
    private static final long INCOMPLETE = 714;
    private static final long NO_SELLABLE_SEATS = 715;
    private static final long CANCELED = 716;
    private static final long NO_SCHEDULES = 717;
    private static final long SEATLESS_VENUE = 718;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static MockHttpServletRequestBuilder as(long userId, String role, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "seller" + userId)
                .header(UserHeaders.ROLE, role);
    }

    private ResultActions publish(long userId, String role, Object performanceId, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request =
                as(userId, role, post("/api/seller/performances/" + performanceId + "/publication"));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request);
    }

    private ResultActions publish(Object performanceId, String ifMatch) throws Exception {
        return publish(SELLER, "SELLER", performanceId, ifMatch);
    }

    private Map<String, Object> performance(long performanceId) {
        return jdbcTemplate.queryForMap(
                "SELECT publication_status, published_at, version FROM p_performances WHERE id = ?", performanceId);
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Nested
    @DisplayName("공개 성공과 멱등")
    class Publish {

        @Test
        @DisplayName("조건을 갖춘 초안을 공개하고 publishedAt을 기록한다. 좌석 준비 FAILED 회차가 있어도 되고, version이 1 오른다")
        void publishesDraft() throws Exception {
            publish(DRAFT, "\"1\"")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(DRAFT))
                    .andExpect(jsonPath("$.data.publicationStatus").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.publishedAt").value("2026-11-01T12:00:00+09:00"))
                    .andExpect(jsonPath("$.data.version").value(2))
                    .andExpect(jsonPath("$.data.sellerId").value(SELLER))
                    .andExpect(jsonPath("$.data.venue.id").value(701))
                    .andExpect(jsonPath("$.data.venue.name").value("공개 공연장"))
                    .andExpect(jsonPath("$.data.description").value("공연 소개"))
                    .andExpect(jsonPath("$.data.posterUrl").value("https://example.com/710.jpg"))
                    // 기간은 활성 회차(720, 728)만 본다. 취소 회차 721과 삭제 회차 722는 빠진다
                    .andExpect(jsonPath("$.data.period.from").value("2026-12-05"))
                    .andExpect(jsonPath("$.data.period.to").value("2026-12-12"));

            assertThat(performance(DRAFT))
                    .containsEntry("publication_status", "PUBLISHED")
                    .containsEntry("version", 2L);

            // 공개 뒤에는 일반 사용자 조회에 보인다
            mockMvc.perform(get("/api/performances/" + DRAFT)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("이미 공개된 공연은 If-Match가 달라도 200과 현재 결과를 주고, 최초 publishedAt과 version을 바꾸지 않는다")
        void isIdempotent() throws Exception {
            publish(PUBLISHED, "\"0\"")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.publicationStatus").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.publishedAt").value("2026-10-05T10:00:00+09:00"))
                    .andExpect(jsonPath("$.data.version").value(3))
                    .andExpect(jsonPath("$.data.period.from").value("2026-12-10"));

            // 응답을 잃고 같은 If-Match로 다시 보낸 경우
            publish(DRAFT, "\"1\"").andExpect(status().isOk());
            clock.set(NOW.plusMinutes(1));
            publish(DRAFT, "\"1\"")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.publishedAt").value("2026-11-01T12:00:00+09:00"))
                    .andExpect(jsonPath("$.data.version").value(2));
            assertThat(performance(DRAFT)).containsEntry("version", 2L);
        }

        @Test
        @DisplayName("공개 뒤 등급별 가격 설정은 409 PERFORMANCE_CONFIGURATION_LOCKED다")
        void locksConfigurationAfterPublication() throws Exception {
            publish(DRAFT, "\"1\"").andExpect(status().isOk());

            expectError(
                    mockMvc.perform(as(SELLER, "SELLER", put("/api/seller/performances/" + DRAFT + "/grade-prices"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("If-Match", "\"2\"")
                            .content("{\"gradePrices\":[{\"venueSeatGradeId\":701,\"price\":170000},"
                                    + "{\"venueSeatGradeId\":702,\"price\":90000}]}")),
                    409,
                    "PERFORMANCE_CONFIGURATION_LOCKED");
        }
    }

    @Nested
    @DisplayName("공개 필수 조건")
    class Requirements {

        @Test
        @DisplayName("부족한 조건을 모두 메시지에 나열하고 409 PUBLICATION_REQUIREMENTS_NOT_MET. 공연은 그대로 초안이다")
        void listsMissingRequirements() throws Exception {
            expectError(publish(INCOMPLETE, "\"0\""), 409, "PUBLICATION_REQUIREMENTS_NOT_MET")
                    .andExpect(jsonPath("$.message", containsString("description, posterUrl, schedules, gradePrices")));

            assertThat(performance(INCOMPLETE))
                    .containsEntry("publication_status", "DRAFT")
                    .containsEntry("published_at", null)
                    .containsEntry("version", 0L);
        }

        @Test
        @DisplayName("좌석이 전부 판매 제외됐거나 공연장에 좌석이 없으면 sellableSeats가 부족하다. 가격 조건은 따지지 않는다")
        void requiresSellableSeats() throws Exception {
            for (long performanceId : new long[] {NO_SELLABLE_SEATS, SEATLESS_VENUE}) {
                expectError(publish(performanceId, "\"0\""), 409, "PUBLICATION_REQUIREMENTS_NOT_MET")
                        .andExpect(jsonPath("$.message", endsWith("부족한 항목: sellableSeats")));
            }
        }
    }

    @Nested
    @DisplayName("오류")
    class Errors {

        @Test
        @DisplayName("없거나 삭제된 공연 404, 남의 공연·SELLER가 아니면 403, 취소된 공연 409 INVALID_STATUS_TRANSITION")
        void rejectsByPerformance() throws Exception {
            expectError(publish(9999, "\"1\""), 404, "PERFORMANCE_NOT_FOUND");
            expectError(publish(DELETED, "\"1\""), 404, "PERFORMANCE_NOT_FOUND");
            expectError(publish(OTHER_SELLERS, "\"1\""), 403, "FORBIDDEN");
            expectError(publish(SELLER, "MASTER", DRAFT, "\"1\""), 403, "FORBIDDEN");
            // 공개된 뒤 취소된 공연도 멱등 200이 아니라 409다
            expectError(publish(CANCELED, "\"4\""), 409, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("초안에서 If-Match가 없으면 428, 다르면 409 VERSION_CONFLICT. 공연 ID가 0 이하면 400")
        void rejectsByIfMatchAndPath() throws Exception {
            expectError(publish(DRAFT, null), 428, "PRECONDITION_REQUIRED");
            expectError(publish(DRAFT, "\"0\""), 409, "VERSION_CONFLICT");
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(publish(id, "\"1\""), 400, "VALIDATION_ERROR");
            }
            assertThat(performance(DRAFT))
                    .containsEntry("publication_status", "DRAFT")
                    .containsEntry("version", 1L);
        }

        @Test
        @DisplayName("이미 공개된 공연도 If-Match가 없으면 428, 형식이 틀리면 400이다. 무시하는 것은 정상 형식의 버전 불일치뿐이다")
        void requiresIfMatchEvenWhenPublished() throws Exception {
            expectError(publish(PUBLISHED, null), 428, "PRECONDITION_REQUIRED");
            for (String ifMatch : new String[] {"W/\"3\"", "*", "\"abc\""}) {
                expectError(publish(PUBLISHED, ifMatch), 400, "VALIDATION_ERROR");
            }
            publish(PUBLISHED, "\"99\"").andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("동시 요청")
    class Concurrency {

        private <T> T[] runTogether(Callable<T> first, Callable<T> second, T[] results) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<T> a = executor.submit(() -> {
                    start.await();
                    return first.call();
                });
                Future<T> b = executor.submit(() -> {
                    start.await();
                    return second.call();
                });
                start.countDown();
                results[0] = a.get(30, TimeUnit.SECONDS);
                results[1] = b.get(30, TimeUnit.SECONDS);
                return results;
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("같은 version으로 공개와 가격 설정이 동시에 오면 하나만 성공하고 다른 하나는 409 VERSION_CONFLICT다")
        void publishAndPriceChange() throws Exception {
            String newPrices = "{\"gradePrices\":[{\"venueSeatGradeId\":701,\"price\":170000},"
                    + "{\"venueSeatGradeId\":702,\"price\":90000}]}";
            MvcResult[] results = runTogether(
                    () -> publish(DRAFT, "\"1\"").andReturn(),
                    () -> mockMvc.perform(
                                    as(SELLER, "SELLER", put("/api/seller/performances/" + DRAFT + "/grade-prices"))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .header("If-Match", "\"1\"")
                                            .content(newPrices))
                            .andReturn(),
                    new MvcResult[2]);

            int published = results[0].getResponse().getStatus();
            int priced = results[1].getResponse().getStatus();
            assertThat(new int[] {published, priced}).containsExactlyInAnyOrder(200, 409);
            MvcResult loser = published == 409 ? results[0] : results[1];
            assertThat((String) JsonPath.read(loser.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("VERSION_CONFLICT");

            // 이긴 쪽만 반영됐다: 공개가 이기면 가격은 그대로, 가격이 이기면 초안이다
            Integer vipPrice = jdbcTemplate.queryForObject(
                    "SELECT price FROM p_performance_grade_prices"
                            + " WHERE performance_id = ? AND venue_seat_grade_id = 701 AND deleted_at IS NULL",
                    Integer.class,
                    DRAFT);
            Map<String, Object> current = performance(DRAFT);
            assertThat(current).containsEntry("version", 2L);
            if (published == 200) {
                assertThat(current).containsEntry("publication_status", "PUBLISHED");
                assertThat(vipPrice).isEqualTo(150000);
            } else {
                assertThat(current).containsEntry("publication_status", "DRAFT");
                assertThat(vipPrice).isEqualTo(170000);
            }
        }

        @Test
        @DisplayName("회차가 없는 공연에 공개와 회차 등록이 동시에 오면 차례로 처리된다. 공개가 성공했다면 등록된 회차를 보고 공개한 것이다")
        void publishAndScheduleRegistration() throws Exception {
            String schedule = "{\"startAt\":\"2026-12-15T19:00:00+09:00\","
                    + "\"bookingOpenAt\":\"2026-11-10T14:00:00+09:00\",\"bookingCloseAt\":\"2026-12-15T18:00:00+09:00\"}";
            MvcResult[] results = runTogether(
                    () -> publish(NO_SCHEDULES, "\"1\"").andReturn(),
                    () -> mockMvc.perform(as(
                                            SELLER,
                                            "SELLER",
                                            post("/api/seller/performances/" + NO_SCHEDULES + "/schedules"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(schedule))
                            .andReturn(),
                    new MvcResult[2]);

            assertThat(results[1].getResponse().getStatus()).isEqualTo(201);
            String publishBody = results[0].getResponse().getContentAsString();
            int published = results[0].getResponse().getStatus();
            if (published == 200) {
                // 회차 등록이 먼저 커밋됐다
                assertThat((String) JsonPath.read(publishBody, "$.data.period.from"))
                        .isEqualTo("2026-12-15");
                assertThat(performance(NO_SCHEDULES)).containsEntry("publication_status", "PUBLISHED");
            } else {
                // 공개가 먼저 잠그고 회차가 없음을 확인했다. 회차 등록은 그 뒤에 처리됐다
                assertThat(published).isEqualTo(409);
                assertThat((String) JsonPath.read(publishBody, "$.code")).isEqualTo("PUBLICATION_REQUIREMENTS_NOT_MET");
                assertThat(performance(NO_SCHEDULES)).containsEntry("publication_status", "DRAFT");
            }
        }
    }

    /**
     * 공개가 공연 행을 FOR UPDATE로 잠그고 나서 회차·가격을 읽는지 확인한다. 위의 동시 요청 테스트는 두 요청이 실제로 겹쳤는지
     * 보장하지 못해(우연히 차례로 실행돼도 통과) 잠금이 빠지는 회귀를 잡지 못한다.
     * 테스트가 별도 연결로 공연 행을 먼저 잠그고 → 공개 요청이 공연 조회(SELECT … FOR UPDATE)에서 기다리는 것을 확인하고 →
     * 그 연결에서 경쟁 변경을 커밋해 잠금을 푼 뒤 → 공개가 커밋된 변경을 보고 처리했는지 본다.
     * Note: 기다리는 쿼리까지 확인한다. 공개의 잠금 조회가 빠지면 나중의 UPDATE가 대신 기다릴 수 있어서 대기 여부만으로는 부족하다.
     */
    @Nested
    @DisplayName("공연 행 잠금")
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

        /**
         * 공연 행을 잠근 채로 공개 요청을 보내고, 공개가 공연 조회의 FOR UPDATE에서 기다리는 것을 확인한 뒤 change를 커밋한다.
         * 공개 응답을 돌려준다.
         */
        private MvcResult publishWhileLocked(long performanceId, String ifMatch, SqlChange change) throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<MvcResult> publishing = null;
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try {
                    int holderPid;
                    try (Statement statement = holder.createStatement();
                            ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
                        pid.next();
                        holderPid = pid.getInt(1);
                        statement.execute("SELECT id FROM p_performances WHERE id = " + performanceId + " FOR UPDATE");
                    }

                    publishing = executor.submit(
                            () -> publish(performanceId, ifMatch).andReturn());

                    String waiting = null;
                    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
                    while (waiting == null && !publishing.isDone() && System.currentTimeMillis() < deadline) {
                        waiting = blockedQuery(holderPid);
                        if (waiting == null) {
                            Thread.sleep(20);
                        }
                    }
                    assertThat(waiting).as("공개 요청이 공연 행 잠금을 기다려야 한다").isNotNull();
                    // Hibernate는 PostgreSQL에서 PESSIMISTIC_WRITE를 FOR NO KEY UPDATE로 보낸다
                    assertThat(waiting.toLowerCase())
                            .as("기다리는 쿼리는 공연 조회의 SELECT … FOR (NO KEY) UPDATE여야 한다")
                            .startsWith("select")
                            .contains("from p_performances")
                            .containsPattern("for (no key )?update");
                    assertThat(publishing.isDone()).isFalse();

                    change.apply(holder);
                    holder.commit();
                    return publishing.get(30, TimeUnit.SECONDS);
                } catch (Exception | AssertionError e) {
                    holder.rollback();
                    awaitQuietly(publishing);
                    throw e;
                }
            } finally {
                executor.shutdownNow();
            }
        }

        /** 실패로 끝날 때 공개 요청이 끝나기를 기다린다. 남겨 두면 다음 테스트의 TRUNCATE와 교착한다. */
        private void awaitQuietly(Future<MvcResult> publishing) {
            if (publishing == null) {
                return;
            }
            try {
                publishing.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 원래 실패를 그대로 알린다
            }
        }

        @Test
        @DisplayName("잠금을 쥔 쪽이 가격을 바꾸고 version을 올리면, 기다리던 공개는 바뀐 version을 보고 409 VERSION_CONFLICT다")
        void waitsForPriceChange() throws Exception {
            MvcResult result = publishWhileLocked(DRAFT, "\"1\"", holder -> {
                try (Statement statement = holder.createStatement()) {
                    statement.executeUpdate("UPDATE p_performance_grade_prices SET price = 170000 WHERE id = 701");
                    statement.executeUpdate("UPDATE p_performances SET version = version + 1 WHERE id = " + DRAFT);
                }
            });

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("VERSION_CONFLICT");
            assertThat(performance(DRAFT))
                    .containsEntry("publication_status", "DRAFT")
                    .containsEntry("version", 2L);
        }

        @Test
        @DisplayName("잠금을 쥔 쪽이 회차를 추가하면(공연 version 유지), 기다리던 공개는 새 회차를 보고 성공하고 period에 반영한다")
        void waitsForScheduleRegistration() throws Exception {
            MvcResult result = publishWhileLocked(NO_SCHEDULES, "\"1\"", holder -> {
                try (Statement statement = holder.createStatement()) {
                    statement.executeUpdate("INSERT INTO p_schedules (performance_id,start_at,booking_open_at,"
                            + "booking_close_at,operation_status,seat_setup_status,seat_config_version,"
                            + "seat_setup_request_id,sales_suspended,seat_setup_attempts,version,created_at,created_by,"
                            + "updated_at,updated_by) VALUES (" + NO_SCHEDULES + ",'2026-12-15 19:00:00',"
                            + "'2026-11-10 14:00:00','2026-12-15 18:00:00','ACTIVE','READY',1,'req-lock',FALSE,1,0,"
                            + "'2026-11-01','seller801','2026-11-01','seller801')");
                }
            });

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            assertThat((String) JsonPath.read(body, "$.data.period.from")).isEqualTo("2026-12-15");
            assertThat((String) JsonPath.read(body, "$.data.period.to")).isEqualTo("2026-12-15");
            assertThat((Integer) JsonPath.read(body, "$.data.version")).isEqualTo(2);
            assertThat(performance(NO_SCHEDULES))
                    .containsEntry("publication_status", "PUBLISHED")
                    .containsEntry("version", 2L);
        }
    }

    /** 잠금을 쥔 연결에서 실행하는 경쟁 변경. */
    @FunctionalInterface
    private interface SqlChange {
        void apply(Connection connection) throws SQLException;
    }

    @Test
    @DisplayName("응답 본문의 nullable 필드는 null로 준다")
    void returnsNullableFields() throws Exception {
        publish(PUBLISHED, "\"3\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.castInfo").value(nullValue()));
    }
}
