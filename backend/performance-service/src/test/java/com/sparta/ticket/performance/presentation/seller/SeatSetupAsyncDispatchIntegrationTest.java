package com.sparta.ticket.performance.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.service.SeatSetupProcessor;
import com.sparta.ticket.performance.application.service.SeatSetupRecoveryService;
import com.sparta.ticket.performance.infrastructure.config.SeatSetupConfig;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 커밋 뒤 좌석 준비의 실제 비동기 경로를 검증한다(이슈 #466).
 * 시험 동안만 공통 부모의 위임 실행기를 운영 설정과 같은 실행기(SeatSetupConfig.seatSetupExecutor)로 바꾼다. 그래서 MockMvc 요청이
 * 실제 API → 컨텍스트의 판매자 서비스 → 컨텍스트의 Dispatcher → 실제 비동기 실행기를 거친다. 좌석 응답은 가짜 클라이언트의 문으로 막는다.
 * Note: 위임 대상은 컨텍스트 공용 상태라, 통합 테스트가 하나씩 실행된다는 전제에 기댄다. 끝나면 @AfterEach가 문을 열고, 실행기를
 * 정리하고, 동기 실행으로 되돌린다(시험이 실패해도 다음 시험에 비동기 작업이 남지 않게).
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/seller-schedule-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SeatSetupAsyncDispatchIntegrationTest extends PerformanceIntegrationTest {

    /** seller-schedule-fixture: 판매자 601의 초안 공연 410. */
    private static final long SCHEDULE_SELLER = 601;

    private static final long SCHEDULE_PERFORMANCE = 410;

    /** seller-performance-fixture: 판매자 501의 초안 공연 310(version 2). 가격을 바꾸면 회차 320·321·323·324를 다시 준비한다. */
    private static final long PRICE_SELLER = 501;

    private static final long PRICE_PERFORMANCE = 310;
    private static final List<Long> RESETUP_SCHEDULES = List.of(320L, 321L, 323L, 324L);
    private static final String PRICES =
            "{\"gradePrices\":[{\"venueSeatGradeId\":301,\"price\":160000},{\"venueSeatGradeId\":302,\"price\":90000}]}";

    private static final String SCHEDULE_BODY = "{\"startAt\":\"2026-12-10T19:00:00+09:00\","
            + "\"bookingOpenAt\":\"2026-11-10T14:00:00+09:00\",\"bookingCloseAt\":\"2026-12-10T18:00:00+09:00\"}";

    private static final long WAIT_SECONDS = 5;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SeatSetupProcessor seatSetupProcessor;

    @Autowired
    private SeatSetupRecoveryService seatSetupRecoveryService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ThreadPoolTaskExecutor asyncExecutor;

    /** 운영 설정과 같은 실행기를 만들어 위임 대상으로 쓴다. */
    private void useAsyncExecutor(int queueCapacity) {
        asyncExecutor = new SeatSetupConfig().seatSetupExecutor(1, queueCapacity, meterRegistry);
        asyncExecutor.initialize();
        seatSetupExecutor().use(asyncExecutor);
    }

    /** 받은 작업을 모두 끝낼 때까지 기다린다. */
    private void drainAsyncExecutor() throws InterruptedException {
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        pool.shutdown();
        assertThat(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void restoreDirectExecutor() throws InterruptedException {
        seatSetupClient.openGate();
        if (asyncExecutor != null) {
            ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
            pool.shutdown();
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
            asyncExecutor.shutdown();
        }
        seatSetupExecutor().useDirect();
    }

    private static MockHttpServletRequestBuilder asSeller(long sellerId, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, String.valueOf(sellerId))
                .header(UserHeaders.USERNAME, "seller" + sellerId)
                .header(UserHeaders.ROLE, "SELLER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long registerSchedule() throws Exception {
        MvcResult result = mockMvc.perform(asSeller(
                                SCHEDULE_SELLER,
                                post("/api/seller/performances/" + SCHEDULE_PERFORMANCE + "/schedules"))
                        .content(SCHEDULE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.seatSetupStatus").value("PENDING"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void replacePrices() throws Exception {
        mockMvc.perform(asSeller(PRICE_SELLER, put("/api/seller/performances/" + PRICE_PERFORMANCE + "/grade-prices"))
                        .header("If-Match", "\"2\"")
                        .content(PRICES))
                .andExpect(status().isOk());
    }

    private String setupStatus(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT seat_setup_status FROM p_schedules WHERE id = ?", String.class, scheduleId);
    }

    private Map<String, Object> seatSetup(long scheduleId) {
        return jdbcTemplate.queryForMap(
                "SELECT seat_setup_status, seat_config_version, seat_setup_request_id FROM p_schedules WHERE id = ?",
                scheduleId);
    }

    private double rejectedCount() {
        return meterRegistry
                .get("performance.seat.setup.dispatch.rejected")
                .counter()
                .count();
    }

    @Test
    @DisplayName("회차 등록 응답은 좌석 응답을 기다리지 않는다. 좌석이 응답하면 READY가 된다")
    void registrationDoesNotWaitForSeatSetup() throws Exception {
        useAsyncExecutor(200);
        seatSetupClient.closeGate();

        long id = registerSchedule();

        // 응답이 돌아온 시점에 좌석 호출은 실행기 스레드에서 막혀 있다(동기였다면 호출이 끝난 뒤에야 응답했다)
        assertThat(seatSetupClient.awaitStarted(1, WAIT_SECONDS)).isTrue();
        assertThat(seatSetupClient.inFlight()).isEqualTo(1);
        assertThat(setupStatus(id)).isEqualTo("PENDING");

        seatSetupClient.openGate();
        drainAsyncExecutor();

        assertThat(setupStatus(id)).isEqualTo("READY");
        assertThat(seatSetupClient.requests()).hasSize(1);
    }

    @Test
    @Sql(
            value = {"/sql/truncate.sql", "/sql/seller-performance-fixture.sql"},
            config = @SqlConfig(encoding = "UTF-8"))
    @DisplayName("가격 설정 응답은 회차들의 좌석 응답을 기다리지 않는다. 풀리면 모든 회차가 현재 구성 버전으로 READY가 된다")
    void priceReplacementDoesNotWaitForSeatSetup() throws Exception {
        useAsyncExecutor(200);
        seatSetupClient.closeGate();

        replacePrices();

        assertThat(seatSetupClient.awaitStarted(1, WAIT_SECONDS)).isTrue();
        assertThat(seatSetupClient.inFlight()).isEqualTo(1);
        assertThat(RESETUP_SCHEDULES)
                .allSatisfy(id -> assertThat(setupStatus(id)).isEqualTo("PENDING"));

        seatSetupClient.openGate();
        drainAsyncExecutor();

        for (Long id : RESETUP_SCHEDULES) {
            Map<String, Object> row = seatSetup(id);
            assertThat(row).containsEntry("seat_setup_status", "READY");
            assertThat(seatSetupClient.requests())
                    .filteredOn(command -> command.scheduleId().equals(id))
                    .extracting(SeatSetupCommand::requestId, SeatSetupCommand::configVersion)
                    .containsExactly(tuple(row.get("seat_setup_request_id"), row.get("seat_config_version")));
        }
    }

    @Test
    @Sql(
            value = {"/sql/truncate.sql", "/sql/seller-performance-fixture.sql"},
            config = @SqlConfig(encoding = "UTF-8"))
    @DisplayName("실행기 작업과 재시도·복구 스케줄러가 겹쳐도 좌석 호출은 동시에 2건을 넘지 않고, 같은 회차를 두 번 보내지 않는다")
    void concurrentSeatCallsStayWithinTwo() throws Exception {
        useAsyncExecutor(200);
        seatSetupClient.closeGate();

        replacePrices();
        assertThat(seatSetupClient.awaitStarted(1, WAIT_SECONDS)).isTrue();

        // 운영의 스케줄링 스레드처럼 스레드 하나에서 재시도와 결과 불명 복구를 차례로 돌린다
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            Future<?> scheduled = scheduler.submit(() -> {
                seatSetupProcessor.processDue(50);
                seatSetupRecoveryService.processDue(20);
            });

            // 실행기 1건과 재시도 1건이 함께 좌석 응답을 기다린다
            assertThat(seatSetupClient.awaitStarted(1, WAIT_SECONDS)).isTrue();
            assertThat(seatSetupClient.inFlight()).isEqualTo(2);

            seatSetupClient.openGate();
            scheduled.get(WAIT_SECONDS, TimeUnit.SECONDS);
            drainAsyncExecutor();
        } finally {
            scheduler.shutdownNow();
        }

        assertThat(seatSetupClient.maxInFlight()).isEqualTo(2);
        assertThat(RESETUP_SCHEDULES)
                .allSatisfy(id -> assertThat(setupStatus(id)).isEqualTo("READY"));
        // 시계가 멈춰 있어 임대가 끝나지 않는다. 두 경로가 같은 회차를 집어도 한쪽만 보낸다
        assertThat(seatSetupClient.requests())
                .extracting(SeatSetupCommand::scheduleId)
                .containsExactlyInAnyOrderElementsOf(RESETUP_SCHEDULES);
    }

    @Test
    @Sql(
            value = {"/sql/truncate.sql", "/sql/seller-performance-fixture.sql"},
            config = @SqlConfig(encoding = "UTF-8"))
    @DisplayName("실행기 큐가 차서 받지 못한 회차는 거절 수를 세고 PENDING으로 남으며, 재시도 스케줄러가 READY로 만든다")
    void rejectedSchedulesAreSentByRetryScheduler() throws Exception {
        // 스레드 1개가 첫 회차에서 막히고 큐 1칸에 하나가 들어가면, 나머지 두 회차는 거절된다
        useAsyncExecutor(1);
        seatSetupClient.closeGate();

        replacePrices();

        assertThat(seatSetupClient.awaitStarted(1, WAIT_SECONDS)).isTrue();
        assertThat(rejectedCount()).isEqualTo(2.0);

        seatSetupClient.openGate();
        drainAsyncExecutor();
        assertThat(seatSetupClient.requests()).hasSize(2);

        // 거절된 두 회차는 PENDING이고 다음 시도 시각이 비어 있어 바로 재시도 대상이다
        assertThat(seatSetupProcessor.processDue(50)).isEqualTo(2);

        assertThat(RESETUP_SCHEDULES)
                .allSatisfy(id -> assertThat(setupStatus(id)).isEqualTo("READY"));
        assertThat(seatSetupClient.requests())
                .extracting(SeatSetupCommand::scheduleId)
                .containsExactlyInAnyOrderElementsOf(RESETUP_SCHEDULES);
    }
}
