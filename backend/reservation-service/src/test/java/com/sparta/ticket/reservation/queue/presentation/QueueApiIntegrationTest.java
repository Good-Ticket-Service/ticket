package com.sparta.ticket.reservation.queue.presentation;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.support.BookingTestConfig;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 대기열 API를 Gateway가 넘겨주는 사용자 헤더와 실제 Redis로 호출한다. 명세는 docs/api.md 대기열 절이다.
 * 대기열이 꺼진 경우(503 QUEUE_DISABLED)는 설정이 컨텍스트 전체에 걸리므로 QueueServiceTest에서 본다.
 */
class QueueApiIntegrationTest extends ReservationIntegrationTest {

    private static final AtomicLong SCHEDULE_IDS = new AtomicLong(2_000_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WaitingQueue waitingQueue;

    @Autowired
    private WaitingRoomRepository waitingRoomRepository;

    @Autowired
    private MutableClock clock;

    /** Redis 만료는 실제 시각으로 동작하므로 고정된 테스트 시계를 지금으로 맞춘다. */
    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.now());
    }

    /** 시계는 컨텍스트를 공유하는 다른 통합 테스트도 쓰므로 원래 시각으로 되돌린다. */
    @AfterEach
    void restoreClock() {
        clock.set(BookingTestConfig.START_TIME);
    }

    /** 지금 열려 있는 room을 만든다. */
    private long newRoom() {
        Instant now = clock.instant();
        return newRoom(now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofHours(1)));
    }

    /** Redis는 테스트끼리 공유하므로 테스트마다 다른 회차로 scope를 만든다. */
    private long newRoom(Instant openAt, Instant closeAt) {
        return waitingRoomRepository
                .create(
                        List.of("schedule:" + SCHEDULE_IDS.incrementAndGet()),
                        openAt,
                        closeAt,
                        closeAt.plus(Duration.ofMinutes(10)))
                .roomId();
    }

    /** 사용자 헤더를 붙여 줄을 선다. 헤더 값은 Gateway가 인증 뒤 넣어 주는 값이다. */
    private ResultActions enter(long roomId, long userId) throws Exception {
        return mockMvc.perform(post("/api/waiting-rooms/{roomId}/entries", roomId)
                .header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, Role.USER.name()));
    }

    @Test
    @DisplayName("줄을 서면 200과 순번, 앞 인원, 예상 대기 시간을 돌려준다")
    void enter() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);

        enter(roomId, 2L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.position").value(2))
                .andExpect(jsonPath("$.data.aheadCount").value(1))
                .andExpect(jsonPath("$.data.estimatedWaitSeconds").value(1));
    }

    @Test
    @DisplayName("이미 서 있는 회원이 다시 부르면 원래 순번을 200으로 돌려준다")
    void reenter() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);
        enter(roomId, 2L);

        enter(roomId, 1L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(1));
    }

    /** 사용자 헤더를 붙여 내 순번을 조회한다. */
    private ResultActions findMyStatus(long roomId, long userId) throws Exception {
        return mockMvc.perform(get("/api/waiting-rooms/{roomId}/entries/me", roomId)
                .header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, Role.USER.name()));
    }

    @Test
    @DisplayName("순번을 조회하면 200과 WAITING, 순번을 돌려주고 응답을 캐시하지 않게 한다")
    void findMyStatus() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);
        enter(roomId, 2L);

        findMyStatus(roomId, 2L)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.position").value(2))
                .andExpect(jsonPath("$.data.aheadCount").value(1))
                .andExpect(jsonPath("$.data.estimatedWaitSeconds").value(1));
    }

    /** 입장 스케줄러가 한 번 돈 것처럼 줄 앞 회원을 입장시킨다. 스케줄러는 테스트에서 꺼져 있다. */
    private void admit(long roomId) {
        waitingQueue.admit(
                roomId,
                new AdmissionPolicy(
                        10, Duration.ofSeconds(2), 1000, Duration.ofMinutes(2), Duration.ofMinutes(10), 1000));
    }

    @Test
    @DisplayName("입장한 회원이 순번을 조회하면 ENTERED와 입장 토큰, +09:00 만료 시각을 주고 순번 필드는 0이다")
    void findMyStatusEntered() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);
        admit(roomId);

        findMyStatus(roomId, 1L)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.status").value("ENTERED"))
                .andExpect(jsonPath("$.data.entryToken").isString())
                .andExpect(jsonPath("$.data.entryExpiresAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.data.position").value(0))
                .andExpect(jsonPath("$.data.aheadCount").value(0))
                .andExpect(jsonPath("$.data.estimatedWaitSeconds").value(0));
    }

    @Test
    @DisplayName("입장한 회원이 다시 진입하면 줄에 다시 서지 않고 순번 조회와 같은 토큰을 받는다")
    void enterWhileEntered() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);
        admit(roomId);
        String token = JsonPath.read(
                findMyStatus(roomId, 1L).andReturn().getResponse().getContentAsString(), "$.data.entryToken");

        enter(roomId, 1L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENTERED"))
                .andExpect(jsonPath("$.data.entryToken").value(token));
        enter(roomId, 2L).andExpect(jsonPath("$.data.position").value(1));
    }

    @Test
    @DisplayName("줄에 없는 회원이 순번을 조회하면 404 NOT_IN_QUEUE")
    void findMyStatusNotInQueue() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);

        findMyStatus(roomId, 2L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_IN_QUEUE"));
    }

    /** 사용자 헤더를 붙여 대기를 취소한다. */
    private ResultActions leave(long roomId, long userId) throws Exception {
        return mockMvc.perform(delete("/api/waiting-rooms/{roomId}/entries/me", roomId)
                .header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, Role.USER.name()));
    }

    @Test
    @DisplayName("대기를 취소하면 204이고, 이후 순번 조회는 404, 뒤 회원의 순번은 당겨진다")
    void leave() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L);
        enter(roomId, 2L);

        leave(roomId, 1L).andExpect(status().isNoContent());

        findMyStatus(roomId, 1L).andExpect(status().isNotFound());
        findMyStatus(roomId, 2L).andExpect(jsonPath("$.data.position").value(1));
    }

    @Test
    @DisplayName("줄에 없는 회원이 취소해도 204다(여러 번 불러도 같다)")
    void leaveNotInQueue() throws Exception {
        long roomId = newRoom();

        leave(roomId, 1L).andExpect(status().isNoContent());
        leave(roomId, 1L).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("없는 room이면 진입·순번 조회 모두 404 WAITING_ROOM_NOT_FOUND")
    void roomNotFound() throws Exception {
        enter(Long.MAX_VALUE, 1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_NOT_FOUND"));
        findMyStatus(Long.MAX_VALUE, 1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("오픈 전에 진입하면 409 WAITING_ROOM_NOT_OPEN")
    void roomNotOpen() throws Exception {
        Instant openAt = clock.instant().plus(Duration.ofMinutes(5));
        long roomId = newRoom(openAt, openAt.plus(Duration.ofHours(1)));

        enter(roomId, 1L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_NOT_OPEN"));
    }

    @Test
    @DisplayName("closeAt이 지나면 줄에 서 있던 회원도 진입·순번 조회 모두 409 WAITING_ROOM_CLOSED")
    void roomClosed() throws Exception {
        long roomId = newRoom();
        enter(roomId, 1L).andExpect(status().isOk());

        clock.set(LocalDateTime.now().plusHours(1));

        enter(roomId, 1L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_CLOSED"));
        findMyStatus(roomId, 1L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_CLOSED"));
    }

    @Test
    @DisplayName("사용자 헤더가 없으면 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/api/waiting-rooms/{roomId}/entries", 1L)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/waiting-rooms/{roomId}/entries/me", 1L)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/waiting-rooms/{roomId}/entries/me", 1L)).andExpect(status().isUnauthorized());
    }
}
