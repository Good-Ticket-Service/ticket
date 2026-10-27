package com.sparta.ticket.user.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.user.application.UserQueryService;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.UserStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UserController.class, properties = "spring.cloud.config.enabled=false")
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserQueryService userQueryService;

    @Test
    @DisplayName("내 정보 조회의 시각은 초 단위로 자르고 +09:00 오프셋을 붙여 응답한다")
    void myInfoTimesHaveSeoulOffset() throws Exception {
        given(userQueryService.getMyInfo(any()))
                .willReturn(new UserResult(
                        1L,
                        "fan@ticket.com",
                        "김태현",
                        "010-1234-5678",
                        Role.USER,
                        UserStatus.ACTIVE,
                        LocalDateTime.of(2026, 10, 25, 14, 30, 0, 597_248_000),
                        LocalDateTime.of(2026, 10, 26, 9, 5, 7)));

        mockMvc.perform(get("/api/users/me")
                        .header(UserHeaders.USER_ID, "1")
                        .header(UserHeaders.USERNAME, "fan@ticket.com")
                        .header(UserHeaders.ROLE, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-25T14:30:00+09:00"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-26T09:05:07+09:00"));
    }
}
