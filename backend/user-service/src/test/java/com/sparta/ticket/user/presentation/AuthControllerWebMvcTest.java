package com.sparta.ticket.user.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.user.application.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Note: @WebMvcTest는 정해진 자동 설정만 올린다. common은 AutoConfigureWebMvc.imports에 웹 공통 설정을 등록해
 * 슬라이스 테스트에서도 공통 예외 처리(공통 에러 형식)가 적용되게 한다. 이 테스트가 그 등록을 지킨다.
 */
@WebMvcTest(controllers = AuthController.class, properties = "spring.cloud.config.enabled=false")
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("슬라이스 테스트에도 common 예외 처리가 들어와 검증 실패를 공통 에러 형식으로 응답한다")
    void sliceUsesCommonExceptionHandler() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/signup"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}
