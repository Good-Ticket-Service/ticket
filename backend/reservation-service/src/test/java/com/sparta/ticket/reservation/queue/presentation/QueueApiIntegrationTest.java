package com.sparta.ticket.reservation.queue.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final AtomicLong PERFORMANCE_IDS = new AtomicLong(2_000_000);

    @Autowired
    private MockMvc mockMvc;

    /** 사용자 헤더를 붙여 줄을 선다. 헤더 값은 Gateway가 인증 뒤 넣어 주는 값이다. */
    private ResultActions enter(long performanceId, long userId) throws Exception {
        return mockMvc.perform(post("/api/queues/performances/{performanceId}", performanceId)
                .header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, Role.USER.name()));
    }

    @Test
    @DisplayName("줄을 서면 200과 순번, 앞 인원, 예상 대기 시간을 돌려준다")
    void enter() throws Exception {
        long performanceId = PERFORMANCE_IDS.incrementAndGet();
        enter(performanceId, 1L);

        enter(performanceId, 2L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.position").value(2))
                .andExpect(jsonPath("$.data.aheadCount").value(1))
                .andExpect(jsonPath("$.data.estimatedWaitSeconds").value(1));
    }

    @Test
    @DisplayName("이미 서 있는 회원이 다시 부르면 원래 순번을 200으로 돌려준다")
    void reenter() throws Exception {
        long performanceId = PERFORMANCE_IDS.incrementAndGet();
        enter(performanceId, 1L);
        enter(performanceId, 2L);

        enter(performanceId, 1L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(1));
    }

    /** 사용자 헤더를 붙여 내 순번을 조회한다. */
    private ResultActions findMyStatus(long performanceId, long userId) throws Exception {
        return mockMvc.perform(get("/api/queues/performances/{performanceId}/me", performanceId)
                .header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "user" + userId + "@ticket.com")
                .header(UserHeaders.ROLE, Role.USER.name()));
    }

    @Test
    @DisplayName("순번을 조회하면 200과 WAITING, 순번을 돌려주고 응답을 캐시하지 않게 한다")
    void findMyStatus() throws Exception {
        long performanceId = PERFORMANCE_IDS.incrementAndGet();
        enter(performanceId, 1L);
        enter(performanceId, 2L);

        findMyStatus(performanceId, 2L)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.position").value(2))
                .andExpect(jsonPath("$.data.aheadCount").value(1))
                .andExpect(jsonPath("$.data.estimatedWaitSeconds").value(1));
    }

    @Test
    @DisplayName("줄에 없는 회원이 순번을 조회하면 404 NOT_IN_QUEUE")
    void findMyStatusNotInQueue() throws Exception {
        long performanceId = PERFORMANCE_IDS.incrementAndGet();
        enter(performanceId, 1L);

        findMyStatus(performanceId, 2L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_IN_QUEUE"));
    }

    @Test
    @DisplayName("사용자 헤더가 없으면 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/api/queues/performances/{performanceId}", 1L)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/queues/performances/{performanceId}/me", 1L)).andExpect(status().isUnauthorized());
    }
}
