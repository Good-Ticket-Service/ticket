package com.sparta.ticket.gateway.config;

import static org.mockito.BDDMockito.given;

import com.sparta.ticket.common.auth.UserHeaders;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/** 사용자 헤더와 내부 경로: 위조 X-User-* 제거·검증값 주입, /internal/** 404, 화이트리스트 통과. */
class UserHeaderSecurityTest extends GatewaySecurityTestSupport {

    @Test
    @DisplayName("유효한 토큰이면 클라이언트가 보낸 X-User-Role을 지우고 저장된 값으로 다시 넣는다")
    void injectsVerifiedHeaders() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));

        client.get()
                .uri("/api/users/sample/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .header(UserHeaders.ROLE, "MASTER")
                .header(UserHeaders.USERNAME, "evil")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$['X-User-Id']")
                .isEqualTo(USER_ID.toString())
                .jsonPath("$['X-Username']")
                .isEqualTo("hong@ticket.com")
                .jsonPath("$['X-User-Role']")
                .isEqualTo("USER");
    }

    @Test
    @DisplayName("소문자로 보낸 x-user-role 위조 헤더도 지운다")
    void stripsLowercaseHeaders() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));

        client.get()
                .uri("/api/users/sample/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .header("x-user-role", "MASTER")
                .header("x-username", "evil")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$['X-User-Role']")
                .isEqualTo("USER")
                .jsonPath("$['X-Username']")
                .isEqualTo("hong@ticket.com");
    }

    @Test
    @DisplayName("로그인은 만료 토큰이 붙어 있어도 인증 없이 통과하고, 위조 헤더는 지운다")
    void whitelistBypassesSecurity() throws Exception {
        client.post()
                .uri("/api/auth/login")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(SECRET, "USER", Instant.now().minusSeconds(60)))
                .header(UserHeaders.ROLE, "MASTER")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$['X-User-Role']")
                .doesNotExist();
    }

    @Test
    @DisplayName("/internal/** 은 토큰이 있어도 404")
    void blocksInternal() throws Exception {
        client.get()
                .uri("/internal/users/" + USER_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("/internal/** 은 토큰이 없어도 401이 아니라 404")
    void blocksInternalWithoutToken() {
        client.get()
                .uri("/internal/users/" + USER_ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_FOUND");
    }
}
