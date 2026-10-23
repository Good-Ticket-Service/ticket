package com.sparta.ticket.common.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class UserContextWebTest {

    private static final String USER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addInterceptors(new RequireRoleInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder request, String role) {
        return request.header(UserHeaders.USER_ID, USER_ID)
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, role);
    }

    @Test
    @DisplayName("@CurrentUser는 Gateway 헤더를 UserContext로 바꿔 준다")
    void resolvesCurrentUser() throws Exception {
        mockMvc.perform(withUser(get("/me"), "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.username").value("hong01"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("사용자 헤더가 없으면 401 UNAUTHORIZED")
    void missingHeaders() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("관리자 역할만 있는 요청은 401 UNAUTHORIZED")
    void rejectsAdminWithRoleOnly() throws Exception {
        mockMvc.perform(get("/admin").header(UserHeaders.ROLE, "MASTER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("사용자 UUID가 잘못된 관리자 요청은 401 UNAUTHORIZED")
    void rejectsAdminWithInvalidUserId() throws Exception {
        mockMvc.perform(get("/admin")
                        .header(UserHeaders.USER_ID, "invalid-uuid")
                        .header(UserHeaders.USERNAME, "hong01")
                        .header(UserHeaders.ROLE, "MASTER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("사용자 이름이 없는 관리자 요청은 401 UNAUTHORIZED")
    void rejectsAdminWithoutUsername() throws Exception {
        mockMvc.perform(get("/admin").header(UserHeaders.USER_ID, USER_ID).header(UserHeaders.ROLE, "MASTER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("@RequireRole(MASTER) API를 USER가 호출하면 403 FORBIDDEN")
    void forbiddenForUser() throws Exception {
        mockMvc.perform(withUser(get("/admin"), "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("@RequireRole(MASTER) API를 MASTER가 호출하면 통과")
    void allowedForMaster() throws Exception {
        mockMvc.perform(withUser(get("/admin"), "MASTER")).andExpect(status().isOk());
    }

    @RestController
    static class SampleController {

        @GetMapping("/me")
        UserContext me(@CurrentUser UserContext user) {
            return user;
        }

        @RequireRole(Role.MASTER)
        @GetMapping("/admin")
        String admin() {
            return "ok";
        }
    }
}
