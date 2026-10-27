package com.sparta.ticket.user.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.user.application.UserCommandService;
import com.sparta.ticket.user.application.UserQueryService;
import com.sparta.ticket.user.application.dto.UpdateMyInfoCommand;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.UserStatus;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = UserController.class, properties = "spring.cloud.config.enabled=false")
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserQueryService userQueryService;

    @MockitoBean
    private UserCommandService userCommandService;

    private static UserResult result(String name, String phone, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new UserResult(1L, "fan@ticket.com", name, phone, Role.USER, UserStatus.ACTIVE, createdAt, updatedAt);
    }

    /** Gateway가 넣어 주는 사용자 헤더를 붙인다. */
    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "fan@ticket.com")
                .header(UserHeaders.ROLE, "USER");
    }

    @Test
    @DisplayName("내 정보 조회의 시각은 초 단위로 자르고 +09:00 오프셋을 붙여 응답한다")
    void myInfoTimesHaveSeoulOffset() throws Exception {
        given(userQueryService.getMyInfo(any()))
                .willReturn(result(
                        "김태현",
                        "010-1234-5678",
                        LocalDateTime.of(2026, 10, 25, 14, 30, 0, 597_248_000),
                        LocalDateTime.of(2026, 10, 26, 9, 5, 7)));

        mockMvc.perform(asUser(get("/api/users/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-25T14:30:00+09:00"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-26T09:05:07+09:00"));
    }

    @Test
    @DisplayName("내 정보 수정은 보낸 필드만 넘기고 수정된 정보를 200으로 응답한다")
    void updateMyInfo() throws Exception {
        given(userCommandService.updateMyInfo(any(), any()))
                .willReturn(result(
                        "홍길동",
                        "010-9876-5432",
                        LocalDateTime.of(2026, 10, 25, 14, 30),
                        LocalDateTime.of(2026, 10, 27, 18, 30, 15, 123_000_000)));

        mockMvc.perform(asUser(patch("/api/users/me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"010-9876-5432\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("010-9876-5432"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-27T18:30:15+09:00"));

        verify(userCommandService).updateMyInfo(any(), eq(new UpdateMyInfoCommand(null, "010-9876-5432")));
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(
                Arguments.of("공백만 있는 이름", "{\"name\":\"   \"}", "name"),
                Arguments.of("빈 문자열 이름", "{\"name\":\"\"}", "name"),
                Arguments.of("50자를 넘는 이름", "{\"name\":\"" + "가".repeat(51) + "\"}", "name"),
                Arguments.of("형식이 틀린 전화번호", "{\"phone\":\"01098765432\"}", "phone"),
                Arguments.of("빈 요청", "{}", "anyFieldPresent"),
                Arguments.of("둘 다 null", "{\"name\":null,\"phone\":null}", "anyFieldPresent"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUpdates")
    @DisplayName("내 정보 수정 요청이 형식에 맞지 않거나 바꿀 값이 없으면 400 VALIDATION_ERROR이고 서비스를 부르지 않는다")
    void rejectsInvalidUpdate(String description, String body, String field) throws Exception {
        mockMvc.perform(asUser(patch("/api/users/me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(field));

        verifyNoInteractions(userCommandService);
    }
}
