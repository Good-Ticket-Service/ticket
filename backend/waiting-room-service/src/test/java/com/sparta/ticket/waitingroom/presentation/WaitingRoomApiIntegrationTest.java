package com.sparta.ticket.waitingroom.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.waitingroom.support.MutableClock;
import com.sparta.ticket.waitingroom.support.WaitingRoomIntegrationTest;
import com.sparta.ticket.waitingroom.support.WaitingRoomTestConfig;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 대기열(room) 생성·조회 API를 실제 Redis로 확인한다. Redis는 테스트끼리 공유하므로 테스트마다 다른 회차 번호로 scope를 만든다.
 * Redis 만료는 실제 시각으로 동작하므로, 고정된 테스트 시계를 지금으로 맞춘 뒤 시작하고 끝나면 되돌린다.
 */
class WaitingRoomApiIntegrationTest extends WaitingRoomIntegrationTest {

    private static final AtomicLong SCHEDULE_IDS = new AtomicLong(6_000_000);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        LocalDateTime current = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(current);
        now = clock.instant().atOffset(KST);
    }

    /** 시계는 컨텍스트를 공유하는 다른 통합 테스트도 쓰므로 원래 시각으로 되돌린다. */
    @AfterEach
    void restoreClock() {
        clock.set(WaitingRoomTestConfig.START_TIME);
    }

    /** 응답은 초가 0이어도 초까지 쓴다. OffsetDateTime.toString()은 초가 0이면 생략하므로 비교에 쓰지 않는다. */
    private static String iso(OffsetDateTime time) {
        return time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String newScope() {
        return "schedule:" + SCHEDULE_IDS.incrementAndGet();
    }

    private ResultActions create(Role role, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/waiting-rooms")
                .header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "admin@ticket.com")
                .header(UserHeaders.ROLE, role.name())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(String scopes, OffsetDateTime openAt, OffsetDateTime closeAt) {
        return """
                {"scopes": %s, "openAt": "%s", "closeAt": "%s"}
                """.formatted(scopes, openAt, closeAt);
    }

    private ResultActions findByScope(String scope) throws Exception {
        return mockMvc.perform(get("/api/waiting-rooms")
                .param("scope", scope)
                .header(UserHeaders.USER_ID, "7")
                .header(UserHeaders.USERNAME, "user7@ticket.com")
                .header(UserHeaders.ROLE, Role.USER.name()));
    }

    @Test
    @DisplayName("관리자가 만들면 201과 room을 돌려주고, 회원은 scope로 그 room을 찾는다")
    void createAndFind() throws Exception {
        String scope1 = newScope();
        String scope2 = newScope();
        OffsetDateTime openAt = now.plusMinutes(5);
        OffsetDateTime closeAt = openAt.plusHours(2);

        create(Role.MASTER, body("[\"%s\", \"%s\", \"%s\"]".formatted(scope1, scope2, scope1), openAt, closeAt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").isNumber())
                .andExpect(jsonPath("$.data.scopes.length()").value(2))
                .andExpect(jsonPath("$.data.openAt").value(iso(openAt)));

        findByScope(scope2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes[0]").value(scope1))
                .andExpect(jsonPath("$.data.closeAt").value(iso(closeAt)));
    }

    @Test
    @DisplayName("관리자가 아니면 403")
    void onlyMaster() throws Exception {
        create(Role.USER, body("[\"%s\"]".formatted(newScope()), now.plusMinutes(5), now.plusHours(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("scope가 비었거나 형식이 틀리거나 ID가 0으로 시작하면 400 VALIDATION_ERROR")
    void invalidScopes() throws Exception {
        create(Role.MASTER, body("[]", now.plusMinutes(5), now.plusHours(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        create(Role.MASTER, body("[\"schedule:1,schedule:2\"]", now.plusMinutes(5), now.plusHours(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("VALIDATION_ERROR")); // Gateway는 URL의 ID를 숫자로 읽어 schedule:12로 비교하므로, 0으로 시작하는
        // ID는 보호되지 않는 room이 된다
        for (String scope : new String[] {"schedule:012", "schedule:0"}) {
            create(Role.MASTER, body("[\"%s\"]".formatted(scope), now.plusMinutes(5), now.plusHours(1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    @DisplayName("openAt이 지금 + 1분보다 이르거나 closeAt이 openAt보다 늦지 않으면 400 INVALID_TIME_RANGE")
    void invalidTimeRange() throws Exception {
        create(Role.MASTER, body("[\"%s\"]".formatted(newScope()), now.plusSeconds(59), now.plusHours(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        OffsetDateTime openAt = now.plusMinutes(5);
        create(Role.MASTER, body("[\"%s\"]".formatted(newScope()), openAt, openAt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    @DisplayName("끝나지 않은 room과 scope가 겹치면 409 WAITING_ROOM_SCOPE_CONFLICT")
    void scopeConflict() throws Exception {
        String scope = newScope();
        create(Role.MASTER, body("[\"%s\"]".formatted(scope), now.plusMinutes(5), now.plusHours(1)))
                .andExpect(status().isCreated());

        create(Role.MASTER, body("[\"%s\"]".formatted(scope), now.plusHours(2), now.plusHours(3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_SCOPE_CONFLICT"));
    }

    @Test
    @DisplayName("scope를 보호하는 room이 없으면 404 WAITING_ROOM_NOT_FOUND")
    void notFound() throws Exception {
        findByScope(newScope())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("내부 목록은 끝나지 않은 room의 scope와 closeAt을 오픈 전 room까지 돌려준다(ApiResponse로 감싸지 않음)")
    void protectedScopes() throws Exception {
        String scope = newScope();
        OffsetDateTime closeAt = now.plusHours(2);
        create(Role.MASTER, body("[\"%s\"]".formatted(scope), now.plusMinutes(5), closeAt))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/internal/waiting-rooms/scopes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[?(@.scopes[0] == '%s')].closeAt".formatted(scope))
                        .value(iso(closeAt)));
    }
}
