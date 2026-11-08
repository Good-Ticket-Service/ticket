package com.sparta.ticket.gateway.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/** 로그인 JWT 검증과 사용자 재확인: 토큰 없음·위조·만료·필수 claim·허용 오차, role 불일치·탈퇴·조회 장애. */
class JwtAuthenticationTest extends GatewaySecurityTestSupport {

    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHORIZED (공통 에러 형식)")
    void missingToken() {
        client.get()
                .uri("/api/v1/events")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UNAUTHORIZED")
                .jsonPath("$.path")
                .isEqualTo("/api/v1/events")
                .jsonPath("$.timestamp")
                .exists();
    }

    @Test
    @DisplayName("다른 키로 서명했거나 만료된 토큰은 401 UNAUTHORIZED이고 user-service를 부르지 않는다")
    void forgedOrExpiredToken() throws Exception {
        String forged = token(
                "attacker-secret-key-0123456789abcdefghij",
                "MASTER",
                Instant.now().plusSeconds(3600));
        String expired = token(SECRET, "USER", Instant.now().minusSeconds(60));

        for (String token : new String[] {forged, expired}) {
            client.get()
                    .uri("/api/v1/events")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("UNAUTHORIZED");
        }
        verify(userLookup, never()).find(any());
    }

    @Test
    @DisplayName("exp가 없거나 role이 없는 토큰은 서명이 맞아도 401 (필수 claim 검증)")
    void requiresClaims() throws Exception {
        String noExp = sign(
                SECRET,
                new JWTClaimsSet.Builder()
                        .subject(USER_ID.toString())
                        .claim("role", "USER")
                        .issueTime(new Date())
                        .build());
        String noRole = sign(
                SECRET,
                new JWTClaimsSet.Builder()
                        .subject(USER_ID.toString())
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                        .build());

        for (String token : new String[] {noExp, noRole}) {
            client.get()
                    .uri("/api/v1/events")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("UNAUTHORIZED");
        }
        verify(userLookup, never()).find(any());
    }

    @Test
    @DisplayName("만료 직후(1초 전) 토큰도 허용 오차 없이 401")
    void noClockSkew() throws Exception {
        client.get()
                .uri("/api/v1/events")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(SECRET, "USER", Instant.now().minusSeconds(1)))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("토큰의 role이 저장된 role과 다르면 401 TOKEN_ROLE_MISMATCH")
    void roleMismatch() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));

        client.get()
                .uri("/api/v1/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("TOKEN_ROLE_MISMATCH");
    }

    @Test
    @DisplayName("탈퇴한 사용자(내부 사용자 조회 404)는 401 TOKEN_ROLE_MISMATCH")
    void withdrawnUser() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.empty()));

        client.get()
                .uri("/api/v1/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("TOKEN_ROLE_MISMATCH");
    }

    @Test
    @DisplayName("user-service 조회가 실패하면 503 SERVICE_UNAVAILABLE")
    void lookupFailure() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.error(new IllegalStateException("down")));

        client.get()
                .uri("/api/v1/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE");
    }
}
