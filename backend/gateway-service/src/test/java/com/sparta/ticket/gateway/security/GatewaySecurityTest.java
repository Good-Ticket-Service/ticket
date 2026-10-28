package com.sparta.ticket.gateway.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sparta.ticket.common.auth.UserHeaders;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 실제 Security 체인과 라우팅을 태운다. 모든 /api/** 요청은 테스트 echo 핸들러로 전달되고, echo는 받은 X-User-* 헤더를 돌려준다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "jwt.secret=" + GatewaySecurityTest.SECRET,
            "auth.whitelist[0]=/api/auth/signup",
            "auth.whitelist[1]=/api/auth/login",
            "auth.user-cache-ttl=60s",
            "spring.cloud.gateway.server.webflux.routes[0].id=echo",
            "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/echo",
            "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/**",
            "spring.cloud.gateway.server.webflux.routes[1].id=missing",
            "spring.cloud.gateway.server.webflux.routes[1].uri=lb://missing-service",
            "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/missing/**"
        })
class GatewaySecurityTest {

    static final String SECRET = "test-only-jwt-secret-key-0123456789abcdef";
    private static final Long USER_ID = 1L;
    private static final UserSnapshot STORED_USER = new UserSnapshot(USER_ID, "hong@ticket.com", "USER");

    @Autowired
    private WebTestClient client;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @MockitoBean
    private UserLookup userLookup;

    @MockitoBean
    private UserServiceClient userServiceClient;

    @Test
    @DisplayName("결제 관리자 요청은 매번 원본 사용자를 확인하고 검증된 헤더를 주입한다")
    void adminBypassesCache() throws Exception {
        given(userServiceClient.fetch(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "master@ticket.com", "MASTER"))));
        for (int i = 0; i < 2; i++) {
            client.get()
                    .uri("/api/admin/payments/refunds")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                    .header(UserHeaders.USER_ID, "999")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$['X-User-Id']")
                    .isEqualTo("1")
                    .jsonPath("$['X-User-Role']")
                    .isEqualTo("MASTER");
        }
        verify(userServiceClient, times(2)).fetch(USER_ID);
        verify(userLookup, never()).find(any());
    }

    @Test
    @DisplayName("결제 관리자 경로는 USER와 SELLER를 403으로 거절한다")
    void adminRejectsNonMaster() throws Exception {
        for (String role : new String[] {"USER", "SELLER"}) {
            given(userServiceClient.fetch(USER_ID))
                    .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "user@ticket.com", role))));
            client.post()
                    .uri("/api/admin/payments/order/refund/retry")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(role))
                    .header(UserHeaders.ROLE, "MASTER")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("FORBIDDEN");
        }
    }

    @Test
    @DisplayName("일반 경로에 MASTER 캐시가 있어도 비활성 사용자는 관리자 명령을 실행하지 못한다")
    void adminDoesNotUseStaleMaster() throws Exception {
        given(userLookup.find(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "master@ticket.com", "MASTER"))));
        client.get()
                .uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isOk();
        given(userServiceClient.fetch(USER_ID)).willReturn(Mono.just(Optional.empty()));
        client.post()
                .uri("/api/admin/payments/order/refund/resume")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        verify(userLookup, times(1)).find(USER_ID);
    }

    @Test
    @DisplayName("사용자 조회 장애 시 캐시로 관리자 권한을 대체하지 않고 503으로 거절한다")
    void adminLookupUnavailable() throws Exception {
        given(userServiceClient.fetch(USER_ID)).willReturn(Mono.error(new IllegalStateException("down")));
        client.post()
                .uri("/api/admin/payments/order/refund/retry")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE");
        verify(userLookup, never()).find(any());
    }

    @Test
    void adminRequiresSignedToken() throws Exception {
        client.get()
                .uri("/api/admin/payments/refunds")
                .header(UserHeaders.ROLE, "MASTER")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/admin/payments/refunds")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer "
                                + token(
                                        "attacker-secret-key-0123456789abcdefghij",
                                        "MASTER",
                                        Instant.now().plusSeconds(3600)))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        verify(userServiceClient, never()).fetch(any());
    }

    @TestConfiguration
    static class EchoConfig {

        @Bean
        RouterFunction<ServerResponse> echo() {
            return RouterFunctions.route(path("/echo"), request -> {
                Map<String, String> headers = new LinkedHashMap<>();
                UserHeaders.ALL.forEach(
                        name -> headers.put(name, request.headers().firstHeader(name)));
                return ServerResponse.ok().bodyValue(headers);
            });
        }
    }

    private static String token(String secret, String role, Instant expiresAt) throws Exception {
        return sign(
                secret,
                new JWTClaimsSet.Builder()
                        .subject(USER_ID.toString())
                        .claim("role", role)
                        .issueTime(Date.from(expiresAt.minusSeconds(3600)))
                        .expirationTime(Date.from(expiresAt))
                        .build());
    }

    private static String sign(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String validToken(String role) throws Exception {
        return token(SECRET, role, Instant.now().plusSeconds(3600));
    }

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
