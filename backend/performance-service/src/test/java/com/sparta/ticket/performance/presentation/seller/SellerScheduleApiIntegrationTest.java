package com.sparta.ticket.performance.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.service.SeatSetupProcessor;
import com.sparta.ticket.performance.support.FakeSeatSetupClient;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 회차 등록과 좌석 준비(1단계 동기: 커밋 뒤 요청 스레드에서 호출)를 실제 PostgreSQL과 가짜 좌석 서비스로 검증한다.
 * fixture는 sql/seller-schedule-fixture.sql이다. 공연 410은 판매자 601의 초안(VIP 150000·R 90000, 좌석 403 판매 제외)이다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/seller-schedule-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SellerScheduleApiIntegrationTest extends PerformanceIntegrationTest {

    private static final long SELLER = 601;
    private static final long PERFORMANCE = 410;
    private static final long OTHER_SELLERS = 411;
    private static final long CANCELED = 412;
    private static final long DELETED = 413;
    private static final long NO_PRICES = 414;
    private static final long NO_SEATS = 415;

    /** 2026-12-10 19:00 회차, 예매 11/10 14:00 ~ 12/10 18:00. 기준 시각(11/1)에는 예매 오픈 전이다. */
    private static final String BODY =
            body("2026-12-10T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-10T18:00:00+09:00");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SeatSetupProcessor seatSetupProcessor;

    private static String body(String startAt, String bookingOpenAt, String bookingCloseAt) {
        return "{\"startAt\":\"" + startAt + "\",\"bookingOpenAt\":\"" + bookingOpenAt + "\",\"bookingCloseAt\":\""
                + bookingCloseAt + "\"}";
    }

    private static MockHttpServletRequestBuilder as(long userId, String role, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "seller" + userId)
                .header(UserHeaders.ROLE, role)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions register(long userId, String role, Object performanceId, String body) throws Exception {
        return mockMvc.perform(as(userId, role, post("/api/seller/performances/" + performanceId + "/schedules"))
                .content(body));
    }

    private ResultActions register(Object performanceId, String body) throws Exception {
        return register(SELLER, "SELLER", performanceId, body);
    }

    /** 등록에 성공하고 새 회차 ID를 돌려준다. */
    private long registerOk(Object performanceId, String body) throws Exception {
        MvcResult result =
                register(performanceId, body).andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private Map<String, Object> schedule(long scheduleId) {
        return jdbcTemplate.queryForMap(
                "SELECT seat_config_version, seat_setup_status, seat_setup_request_id, seat_setup_error,"
                        + " seat_setup_attempts, seat_setup_next_attempt_at, version FROM p_schedules WHERE id = ?",
                scheduleId);
    }

    private int scheduleCount(long performanceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_schedules WHERE performance_id = ?", Integer.class, performanceId);
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Timestamp at(java.time.LocalDateTime time) {
        return Timestamp.valueOf(time);
    }

    @Nested
    @DisplayName("POST /api/seller/performances/{performanceId}/schedules 회차 등록")
    class Register {

        @Test
        @DisplayName("201·Location·P13을 주고, 응답은 등록 시점의 PENDING이다. 커밋 뒤 좌석 준비가 성공하면 READY가 된다")
        void registersSchedule() throws Exception {
            MvcResult result = register(PERFORMANCE, BODY)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.performanceId").value(PERFORMANCE))
                    .andExpect(jsonPath("$.data.startAt").value("2026-12-10T19:00:00+09:00"))
                    .andExpect(jsonPath("$.data.bookingOpenAt").value("2026-11-10T14:00:00+09:00"))
                    .andExpect(jsonPath("$.data.bookingCloseAt").value("2026-12-10T18:00:00+09:00"))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.seatSetupStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data.salesSuspended").value(false))
                    .andExpect(jsonPath("$.data.salesStatus").value("BEFORE_OPEN"))
                    .andExpect(jsonPath("$.data.canceledAt").value(nullValue()))
                    .andExpect(jsonPath("$.data.seatConfigVersion").value(1))
                    .andExpect(jsonPath("$.data.seatSetupRequestId", hasLength(36)))
                    .andExpect(jsonPath("$.data.seatSetupError").value(nullValue()))
                    .andExpect(jsonPath("$.data.suspensionReason").value(nullValue()))
                    .andExpect(jsonPath("$.data.cancelReason").value(nullValue()))
                    .andExpect(jsonPath("$.data.version").value(0))
                    .andReturn();
            String json = result.getResponse().getContentAsString();
            long id = ((Number) JsonPath.read(json, "$.data.id")).longValue();
            String requestId = JsonPath.read(json, "$.data.seatSetupRequestId");
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/seller/schedules/" + id);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_setup_attempts", 1)
                    .containsEntry("seat_setup_next_attempt_at", null)
                    // 좌석 준비 진행은 회차 명령이 아니라 version(If-Match 기준)을 올리지 않는다
                    .containsEntry("version", 0L)
                    .containsEntry("seat_setup_request_id", requestId);

            // 좌석 서비스에 보낸 요청: 삭제 좌석(404)은 빼고, 판매 제외 좌석(403)은 isSellable=false로 가격과 함께 보낸다
            assertThat(seatSetupClient.requests()).hasSize(1);
            SeatSetupCommand sent = seatSetupClient.requests().get(0);
            assertThat(sent.requestId()).isEqualTo(requestId);
            assertThat(sent.scheduleId()).isEqualTo(id);
            assertThat(sent.configVersion()).isEqualTo(1);
            assertThat(sent.seats())
                    .containsExactly(
                            new SeatSetupCommand.Line(401L, 401L, "VIP", 150000, true, "A", "1", 1),
                            new SeatSetupCommand.Line(402L, 401L, "VIP", 150000, true, "A", "1", 2),
                            new SeatSetupCommand.Line(403L, 402L, "R", 90000, false, "B", "2", 1));
        }

        @Test
        @DisplayName("좌석 서비스를 부르는 동안 DB 커넥션을 쥐지 않는다(호출이 트랜잭션 밖이다)")
        void doesNotHoldConnectionWhileCallingSeatService() throws Exception {
            registerOk(PERFORMANCE, BODY);

            assertThat(seatSetupClient.activeConnections()).containsExactly(0);
        }

        @Test
        @DisplayName("다른 오프셋으로 보낸 시각도 KST로 저장한다. 같은 순간이면 409 DUPLICATE_SCHEDULE이다")
        void comparesStartAtAsKst() throws Exception {
            // 회차 420은 2026-12-01 19:00(KST) = 10:00Z
            expectError(
                    register(PERFORMANCE, body("2026-12-01T10:00:00Z", "2026-11-10T05:00:00Z", "2026-12-01T09:00:00Z")),
                    409,
                    "DUPLICATE_SCHEDULE");
            assertThat(scheduleCount(PERFORMANCE)).isEqualTo(2);
            assertThat(seatSetupClient.requests()).isEmpty();
        }

        @Test
        @DisplayName("삭제된 회차와 같은 시각은 새 회차로 등록한다")
        void registersSameTimeAsDeletedSchedule() throws Exception {
            long id = registerOk(
                    PERFORMANCE,
                    body("2026-12-02T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-02T18:00:00+09:00"));

            assertThat(id).isNotEqualTo(421);
        }

        @Test
        @DisplayName("예매 오픈 < 예매 마감 ≤ 공연 시작이 아니면 400 INVALID_TIME_RANGE. 마감과 시작이 같으면 된다")
        void validatesTimeRange() throws Exception {
            String openEqualsClose =
                    body("2026-12-10T19:00:00+09:00", "2026-12-10T18:00:00+09:00", "2026-12-10T18:00:00+09:00");
            String closeAfterStart =
                    body("2026-12-10T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-10T19:00:01+09:00");
            for (String body : new String[] {openEqualsClose, closeAfterStart}) {
                expectError(register(PERFORMANCE, body), 400, "INVALID_TIME_RANGE");
            }
            // 시각 순서는 공연을 찾기 전에 확인한다
            expectError(register(9999, openEqualsClose), 400, "INVALID_TIME_RANGE");
            assertThat(scheduleCount(PERFORMANCE)).isEqualTo(2);

            registerOk(
                    PERFORMANCE,
                    body("2026-12-10T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-10T19:00:00+09:00"));
        }

        @Test
        @DisplayName("시각이 없거나 오프셋이 없거나 형식이 틀리면 400 VALIDATION_ERROR")
        void validatesRequest() throws Exception {
            String[] bodies = {
                "{\"startAt\":\"2026-12-10T19:00:00+09:00\",\"bookingOpenAt\":\"2026-11-10T14:00:00+09:00\"}",
                body("2026-12-10T19:00:00", "2026-11-10T14:00:00+09:00", "2026-12-10T18:00:00+09:00"),
                body("내일", "2026-11-10T14:00:00+09:00", "2026-12-10T18:00:00+09:00")
            };
            for (String body : bodies) {
                expectError(register(PERFORMANCE, body), 400, "VALIDATION_ERROR");
            }
        }

        @Test
        @DisplayName("없거나 삭제된 공연은 404, 남의 공연·SELLER가 아니면 403, 취소된 공연은 409 INVALID_STATUS_TRANSITION, 공연 ID가 0 이하면 400")
        void rejectsByPerformance() throws Exception {
            expectError(register(9999, BODY), 404, "PERFORMANCE_NOT_FOUND");
            expectError(register(DELETED, BODY), 404, "PERFORMANCE_NOT_FOUND");
            expectError(register(OTHER_SELLERS, BODY), 403, "FORBIDDEN");
            expectError(register(SELLER, "MASTER", PERFORMANCE, BODY), 403, "FORBIDDEN");
            expectError(register(CANCELED, BODY), 409, "INVALID_STATUS_TRANSITION");
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(register(id, BODY), 400, "VALIDATION_ERROR");
            }

            assertThat(seatSetupClient.requests()).isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /api/seller/schedules/{scheduleId} 판매자 회차 상세")
    class Detail {

        private ResultActions detail(long userId, String role, Object scheduleId) throws Exception {
            return mockMvc.perform(as(userId, role, get("/api/seller/schedules/" + scheduleId)));
        }

        private ResultActions detail(Object scheduleId) throws Exception {
            return detail(SELLER, "SELLER", scheduleId);
        }

        @Test
        @DisplayName("P13과 ETag를 준다. 좌석 준비 실패 요약과 판매 중지 사유를 보여 주고, 판매 중지된 예매 기간이면 UNAVAILABLE이다")
        void returnsScheduleWithETag() throws Exception {
            detail(430)
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"2\""))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(430))
                    .andExpect(jsonPath("$.data.performanceId").value(NO_SEATS))
                    .andExpect(jsonPath("$.data.startAt").value("2026-12-20T19:00:00+09:00"))
                    .andExpect(jsonPath("$.data.bookingOpenAt").value("2026-10-20T14:00:00+09:00"))
                    .andExpect(jsonPath("$.data.bookingCloseAt").value("2026-12-20T18:00:00+09:00"))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.seatSetupStatus").value("FAILED"))
                    .andExpect(jsonPath("$.data.salesSuspended").value(true))
                    .andExpect(jsonPath("$.data.salesStatus").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.data.canceledAt").value(nullValue()))
                    .andExpect(jsonPath("$.data.seatConfigVersion").value(2))
                    .andExpect(jsonPath("$.data.seatSetupRequestId").value("req-430"))
                    .andExpect(jsonPath("$.data.seatSetupError").value("공연장에 좌석이 없습니다."))
                    .andExpect(jsonPath("$.data.suspensionReason").value("좌석 점검"))
                    .andExpect(jsonPath("$.data.cancelReason").value(nullValue()))
                    .andExpect(jsonPath("$.data.version").value(2));
        }

        @Test
        @DisplayName("취소된 회차, 초안 공연의 회차, 취소된 공연의 회차도 조회한다")
        void returnsCanceledAndDraftSchedules() throws Exception {
            detail(431)
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"3\""))
                    .andExpect(jsonPath("$.data.operationStatus").value("CANCELED"))
                    .andExpect(jsonPath("$.data.salesStatus").value("CANCELED"))
                    .andExpect(jsonPath("$.data.canceledAt").value("2026-10-20T10:00:00+09:00"))
                    .andExpect(jsonPath("$.data.cancelReason").value("공연장 사정"));

            // 공연 410은 초안이다. 공개 조회에서는 404지만 판매자 조회는 된다
            detail(420)
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"0\""))
                    .andExpect(jsonPath("$.data.performanceId").value(PERFORMANCE))
                    .andExpect(jsonPath("$.data.salesStatus").value("BEFORE_OPEN"));

            // 공연이 취소되면 회차의 operationStatus는 ACTIVE여도 판매 상태는 CANCELED다
            detail(434)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.salesStatus").value("CANCELED"));
        }

        @Test
        @DisplayName("회차 등록의 Location으로 조회하면 좌석 준비 결과(READY)와 등록 때와 같은 version을 준다")
        void followsRegistrationLocation() throws Exception {
            MvcResult registered =
                    register(PERFORMANCE, BODY).andExpect(status().isCreated()).andReturn();
            String location = registered.getResponse().getHeader("Location");

            mockMvc.perform(as(SELLER, "SELLER", get(location)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"0\""))
                    .andExpect(jsonPath("$.data.seatSetupStatus").value("READY"))
                    .andExpect(jsonPath("$.data.version").value(0));
        }

        @Test
        @DisplayName("회차나 부모 공연이 없거나 삭제됐으면 404 SCHEDULE_NOT_FOUND, 남의 공연의 회차·SELLER가 아니면 403")
        void rejects() throws Exception {
            expectError(detail(9999), 404, "SCHEDULE_NOT_FOUND");
            expectError(detail(421), 404, "SCHEDULE_NOT_FOUND");
            expectError(detail(433), 404, "SCHEDULE_NOT_FOUND");
            expectError(detail(432), 403, "FORBIDDEN");
            expectError(detail(SELLER, "MASTER", 420), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("회차 ID가 숫자가 아니거나 0 이하면 400 VALIDATION_ERROR")
        void rejectsInvalidId() throws Exception {
            for (String id : new String[] {"abc", "0", "-1"}) {
                expectError(detail(id), 400, "VALIDATION_ERROR");
            }
        }
    }

    @Nested
    @DisplayName("좌석 준비 결과 처리")
    class SeatSetup {

        @Test
        @DisplayName("일시 실패면 PENDING으로 두고, 다음 시도 시각이 지나면 재시도 스케줄러가 같은 requestId로 다시 보낸다")
        void retriesWithSameRequestId() throws Exception {
            seatSetupClient.answer(new SeatSetupOutcome.Unavailable("timeout"));

            long id = registerOk(PERFORMANCE, BODY);

            Map<String, Object> pending = schedule(id);
            assertThat(pending)
                    .containsEntry("seat_setup_status", "PENDING")
                    .containsEntry("seat_setup_attempts", 1)
                    .containsEntry("seat_setup_next_attempt_at", at(NOW.plusSeconds(10)))
                    .containsEntry("seat_setup_error", null);

            // 다음 시도 시각 전에는 보내지 않는다
            clock.set(NOW.plusSeconds(9));
            assertThat(seatSetupProcessor.processDue(50)).isZero();

            clock.set(NOW.plusSeconds(10));
            assertThat(seatSetupProcessor.processDue(50)).isEqualTo(1);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_setup_attempts", 2)
                    .containsEntry("version", 0L)
                    .containsEntry("seat_setup_next_attempt_at", null);
            assertThat(seatSetupClient.requests())
                    .extracting(SeatSetupCommand::requestId)
                    .containsOnly((String) pending.get("seat_setup_request_id"))
                    .hasSize(2);
        }

        @Test
        @DisplayName("일시 실패가 8번이면 FAILED와 안전한 오류 요약. 간격은 10초에서 두 배씩 늘고 상한은 5분이다(약 15분)")
        void failsAfterMaxAttempts() throws Exception {
            for (int i = 0; i < 8; i++) {
                seatSetupClient.answer(new SeatSetupOutcome.Unavailable("503"));
            }
            long id = registerOk(PERFORMANCE, BODY);

            java.time.LocalDateTime now = waitThroughRetries(id, new long[] {10, 20, 40, 80, 160, 300, 300});

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_attempts", 8)
                    .containsEntry("seat_setup_next_attempt_at", null)
                    .containsEntry("seat_setup_error", "좌석 준비가 반복해서 실패했습니다. 운영자에게 문의해 주세요.");
            assertThat(seatSetupClient.requests()).hasSize(8);

            clock.set(now.plusHours(1));
            assertThat(seatSetupProcessor.processDue(50)).isZero();
        }

        /** 다음 시도 시각을 확인하며 시계를 옮기고 재시도를 돌린다. 마지막 시각을 돌려준다. */
        private java.time.LocalDateTime waitThroughRetries(long id, long[] delays) {
            java.time.LocalDateTime now = NOW;
            for (long delay : delays) {
                assertThat(schedule(id)).containsEntry("seat_setup_next_attempt_at", at(now.plusSeconds(delay)));
                now = now.plusSeconds(delay);
                clock.set(now);
                seatSetupProcessor.processDue(50);
            }
            return now;
        }

        @Test
        @DisplayName("409 SEAT_SETUP_IN_USE는 좌석이 풀리길 기다리며 다시 보내고, 풀리면 READY가 된다")
        void retriesWhileSeatsInUse() throws Exception {
            seatSetupClient.answer(new SeatSetupOutcome.Unavailable("SEAT_SETUP_IN_USE", "선점·판매 중인 좌석이 있습니다."));

            long id = registerOk(PERFORMANCE, BODY);
            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "PENDING")
                    .containsEntry("seat_setup_error", null);

            clock.set(NOW.plusSeconds(10));
            seatSetupProcessor.processDue(50);

            assertThat(schedule(id)).containsEntry("seat_setup_status", "READY");
        }

        @Test
        @DisplayName("409 SEAT_SETUP_IN_USE가 한도까지 이어지면 FAILED이고, 사유는 선점·판매 중인 좌석이다")
        void failsWithInUseReason() throws Exception {
            for (int i = 0; i < 8; i++) {
                seatSetupClient.answer(new SeatSetupOutcome.Unavailable("SEAT_SETUP_IN_USE", "선점·판매 중인 좌석이 있습니다."));
            }
            long id = registerOk(PERFORMANCE, BODY);

            waitThroughRetries(id, new long[] {10, 20, 40, 80, 160, 300, 300});

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_error", "선점·판매 중인 좌석이 있습니다.");
        }

        @Test
        @DisplayName("마지막 시도가 진행 중일 때 다른 실행이 같은 회차를 잡아도 FAILED로 확정하지 않고, 마지막 시도의 성공을 반영한다")
        void doesNotFailLastAttemptInFlight() throws Exception {
            for (int i = 0; i < 7; i++) {
                seatSetupClient.answer(new SeatSetupOutcome.Unavailable("503"));
            }
            long[] staleRuns = new long[1];
            seatSetupClient.answer(command -> {
                // 마지막(8번째) 시도가 좌석 응답을 기다리는 사이, 같은 회차를 오래된 후보로 잡은 다른 서버의 실행을 흉내 낸다
                seatSetupProcessor.process(command.scheduleId());
                staleRuns[0]++;
                return FakeSeatSetupClient.ready(command);
            });
            long id = registerOk(PERFORMANCE, BODY);

            waitThroughRetries(id, new long[] {10, 20, 40, 80, 160, 300, 300});

            assertThat(staleRuns[0]).isEqualTo(1);
            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_setup_attempts", 8)
                    .containsEntry("seat_setup_error", null);
            assertThat(seatSetupClient.requests()).hasSize(8);
        }

        @Test
        @DisplayName("마지막 시도 중 서버가 멈춰 결과가 없으면, 임대 시간이 지난 뒤에만 보내지 않고 FAILED로 정리한다")
        void failsAbandonedLastAttemptAfterLease() throws Exception {
            long id = registerOk(PERFORMANCE, BODY);
            // 8번째 시도를 시작(임대 1분)한 뒤 결과를 반영하지 못하고 멈춘 상태
            jdbcTemplate.update(
                    "UPDATE p_schedules SET seat_setup_status = 'PENDING', seat_setup_attempts = 8,"
                            + " seat_setup_next_attempt_at = ? WHERE id = ?",
                    at(NOW.plusMinutes(1)),
                    id);

            assertThat(seatSetupProcessor.processDue(50)).isZero();
            assertThat(schedule(id)).containsEntry("seat_setup_status", "PENDING");

            clock.set(NOW.plusMinutes(1));
            assertThat(seatSetupProcessor.processDue(50)).isEqualTo(1);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_next_attempt_at", null)
                    .containsEntry("seat_setup_error", "좌석 준비가 반복해서 실패했습니다. 운영자에게 문의해 주세요.");
            // 등록 때의 첫 호출 한 번뿐이다
            assertThat(seatSetupClient.requests()).hasSize(1);
        }

        @Test
        @DisplayName("400은 재시도하지 않고 FAILED, 409 SEAT_SETUP_STALE_VERSION은 결과를 무시한다")
        void handlesDefinitiveResults() throws Exception {
            seatSetupClient.answer(new SeatSetupOutcome.Rejected("VALIDATION_ERROR", "요청 데이터 오류입니다."));
            long rejected = registerOk(PERFORMANCE, BODY);
            assertThat(schedule(rejected))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_error", "요청 데이터 오류입니다.");
            assertThat(seatSetupClient.requests()).hasSize(1);

            seatSetupClient.answer(new SeatSetupOutcome.Stale());
            long stale = registerOk(
                    PERFORMANCE,
                    body("2026-12-11T19:00:00+09:00", "2026-11-10T14:00:00+09:00", "2026-12-11T18:00:00+09:00"));
            assertThat(schedule(stale))
                    .containsEntry("seat_setup_status", "PENDING")
                    .containsEntry("seat_setup_attempts", 1);
        }

        @Test
        @DisplayName("응답의 requestId·configVersion이 요청과 다르면 READY로 두지 않고 일시 실패로 다시 보낸다")
        void rejectsMismatchedResponse() throws Exception {
            seatSetupClient.answer(command -> new SeatSetupOutcome.Ready("다른 요청", command.configVersion(), 3, 2));

            long id = registerOk(PERFORMANCE, BODY);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "PENDING")
                    .containsEntry("seat_setup_attempts", 1);
        }

        @Test
        @DisplayName("호출 중에 구성이 바뀌었으면(새 requestId·configVersion) 지난 요청의 결과를 반영하지 않는다")
        void ignoresResultOfOldRequest() throws Exception {
            seatSetupClient.answer(command -> {
                jdbcTemplate.update(
                        "UPDATE p_schedules SET seat_config_version = 2, seat_setup_request_id = 'req-new',"
                                + " seat_setup_attempts = 0 WHERE id = ?",
                        command.scheduleId());
                return FakeSeatSetupClient.ready(command);
            });

            long id = registerOk(PERFORMANCE, BODY);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "PENDING")
                    .containsEntry("seat_config_version", 2L)
                    .containsEntry("seat_setup_request_id", "req-new");
        }

        @Test
        @DisplayName("판매 대상 좌석의 가격이 없으면 보내지 않고 FAILED. 가격을 설정하면 새 구성 버전으로 다시 준비해 READY가 된다")
        void failsWithoutPriceAndRecoversByPricing() throws Exception {
            long id = registerOk(NO_PRICES, BODY);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_error", "판매 대상 좌석 등급의 가격이 없습니다. 등급별 가격을 설정해 주세요.");
            assertThat(seatSetupClient.requests()).isEmpty();

            mockMvc.perform(as(SELLER, "SELLER", put("/api/seller/performances/" + NO_PRICES + "/grade-prices"))
                            .header("If-Match", "\"0\"")
                            .content("{\"gradePrices\":[{\"venueSeatGradeId\":401,\"price\":120000},"
                                    + "{\"venueSeatGradeId\":402,\"price\":80000}]}"))
                    .andExpect(status().isOk());

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "READY")
                    .containsEntry("seat_config_version", 2L)
                    .containsEntry("seat_setup_error", null);
            assertThat(seatSetupClient.requests()).singleElement().satisfies(request -> {
                assertThat(request.configVersion()).isEqualTo(2);
                assertThat(request.seats())
                        .extracting(SeatSetupCommand.Line::basePrice)
                        .contains(120000, 80000);
            });
        }

        @Test
        @DisplayName("공연장에 좌석이 없으면 보내지 않고 FAILED")
        void failsWithoutSeats() throws Exception {
            long id = registerOk(NO_SEATS, BODY);

            assertThat(schedule(id))
                    .containsEntry("seat_setup_status", "FAILED")
                    .containsEntry("seat_setup_error", "공연장에 좌석이 없어 좌석을 준비할 수 없습니다.");
            assertThat(seatSetupClient.requests()).isEmpty();
        }
    }

    @Nested
    @DisplayName("동시 요청")
    class Concurrency {

        @Test
        @DisplayName("회차 등록과 가격 변경이 동시에 와도 공연 행 잠금으로 차례로 처리되고, 회차는 바뀐 가격으로 준비된다")
        void registerAndPriceChange() throws Exception {
            String newPrices = "{\"gradePrices\":[{\"venueSeatGradeId\":401,\"price\":170000},"
                    + "{\"venueSeatGradeId\":402,\"price\":90000}]}";
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Callable<MvcResult> registerTask = () -> {
                    start.await();
                    return register(PERFORMANCE, BODY).andReturn();
                };
                Callable<MvcResult> priceTask = () -> {
                    start.await();
                    return mockMvc.perform(as(
                                            SELLER,
                                            "SELLER",
                                            put("/api/seller/performances/" + PERFORMANCE + "/grade-prices"))
                                    .header("If-Match", "\"1\"")
                                    .content(newPrices))
                            .andReturn();
                };
                Future<MvcResult> registered = executor.submit(registerTask);
                Future<MvcResult> priced = executor.submit(priceTask);
                start.countDown();

                MvcResult registerResult = registered.get(30, TimeUnit.SECONDS);
                assertThat(registerResult.getResponse().getStatus()).isEqualTo(201);
                assertThat(priced.get(30, TimeUnit.SECONDS).getResponse().getStatus())
                        .isEqualTo(200);
                long id = ((Number) JsonPath.read(registerResult.getResponse().getContentAsString(), "$.data.id"))
                        .longValue();

                // 등록이 먼저면 가격 변경이 구성 버전을 2로 올려 다시 준비하고, 가격 변경이 먼저면 새 가격으로 버전 1에서 준비한다.
                // 어느 쪽이든 READY로 반영된 요청은 회차의 현재 requestId·구성 버전이고 바뀐 가격(170000)을 담는다.
                // 두 스레드의 호출 순서는 정해지지 않으므로 기록 순서가 아니라 현재 값으로 찾는다
                Map<String, Object> current = schedule(id);
                assertThat(current).containsEntry("seat_setup_status", "READY");
                List<SeatSetupCommand> applied = seatSetupClient.requests().stream()
                        .filter(request -> request.requestId().equals(current.get("seat_setup_request_id")))
                        .toList();
                assertThat(applied).singleElement().satisfies(request -> {
                    assertThat(request.scheduleId()).isEqualTo(id);
                    assertThat(request.configVersion()).isEqualTo(current.get("seat_config_version"));
                    assertThat(request.seats())
                            .filteredOn(seat -> seat.venueSeatId() == 401)
                            .extracting(SeatSetupCommand.Line::basePrice)
                            .containsExactly(170000);
                });
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
