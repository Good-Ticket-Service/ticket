package com.sparta.ticket.gateway.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/** Security 밖 오류의 공통 형식: 방화벽 400, 라우트 없음 404, 인스턴스 없음 503. */
class GatewayErrorResponseTest extends GatewaySecurityTestSupport {

    @Test
    @DisplayName("경로 우회 시도는 방화벽이 막고 공통 에러 형식의 400으로 응답한다")
    void rejectsPathTraversal() throws Exception {
        for (String path : new String[] {
            "/api/auth/login/../../users/sample/me", "/api/users/..%2f..%2finternal/users/x", "/api/auth/login;/x"
        }) {
            client.get()
                    .uri(URI.create("http://localhost:" + port + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    @DisplayName("라우트가 없는 경로는 공통 에러 형식의 404")
    void noRoute() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));

        client.get()
                .uri("/nope")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_FOUND")
                .jsonPath("$.path")
                .isEqualTo("/nope");
    }

    @Test
    @DisplayName("대상 서비스 인스턴스가 없으면 공통 에러 형식의 503 SERVICE_UNAVAILABLE")
    void noInstance() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));

        client.get()
                .uri("/missing/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE");
    }
}
