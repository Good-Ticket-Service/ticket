package com.sparta.ticket.gateway.queue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sparta.ticket.gateway.auth.UserLookup;
import com.sparta.ticket.gateway.auth.UserSnapshot;
import com.sparta.ticket.gateway.infrastructure.client.UserServiceClient;
import com.sparta.ticket.gateway.queue.ProtectedScopes.Protection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
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
 * 실제 Security 체인과 라우팅을 태워 좌석 경로의 입장 토큰 검사를 확인한다. 통과한 요청은 테스트 echo 핸들러가 200으로 받는다.
 * 보호 목록(ProtectedScopes)은 대역으로 바꿔, 이 테스트의 회차(schedule:{scheduleId})만 보호 중이고 나머지는 보호하지 않게 둔다.
 * 보호 중인 회차는 토큰의 scopes에 그 회차가 있어야 통과한다. 테스트마다 다른 회차 ID를 쓴다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "jwt.secret=" + QueueTokenSecurityTest.LOGIN_SECRET,
            "queue.token-secret=" + QueueTokenSecurityTest.QUEUE_SECRET,
            "auth.whitelist[0]=/api/seats/schedules/*/remaining",
            "spring.cloud.gateway.server.webflux.routes[0].id=echo",
            "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/echo",
            "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/**"
        })
class QueueTokenSecurityTest {

    static final String LOGIN_SECRET = "test-only-jwt-secret-key-0123456789abcdef";
    static final String QUEUE_SECRET = "test-only-queue-token-secret-0123456789abcdef";
    private static final long USER_ID = 7L;
    private static final long ROOM_ID = 3L;
    private static final AtomicLong SCHEDULE_IDS = new AtomicLong(1000);

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private UserLookup userLookup;

    @MockitoBean
    private UserServiceClient userServiceClient;

    @MockitoBean
    private ProtectedScopes protectedScopes;

    private long scheduleId;

    @BeforeEach
    void setUp() {
        scheduleId = SCHEDULE_IDS.incrementAndGet();
        given(protectedScopes.protection(anyString())).willReturn(Protection.UNPROTECTED);
        given(protectedScopes.protection(scope(scheduleId))).willReturn(Protection.PROTECTED);
        given(userLookup.find(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "hong@ticket.com", "USER"))));
    }

    private static String sign(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String loginToken() throws Exception {
        Instant now = Instant.now();
        return sign(
                LOGIN_SECRET,
                new JWTClaimsSet.Builder()
                        .subject(String.valueOf(USER_ID))
                        .claim("role", "USER")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .build());
    }

    /** waiting-room-service가 발급하는 모양의 입장 토큰. */
    private static String entryToken(String secret, long userId, List<String> scopes, Instant expiresAt)
            throws Exception {
        return sign(
                secret,
                new JWTClaimsSet.Builder()
                        .subject(String.valueOf(userId))
                        .claim("roomId", ROOM_ID)
                        .claim("scopes", scopes)
                        .issueTime(Date.from(expiresAt.minusSeconds(600)))
                        .expirationTime(Date.from(expiresAt))
                        .build());
    }

    /** 이 테스트의 회차를 scope에 담은 토큰. 다른 회차도 함께 담아 여러 회차를 보호하는 room을 흉내 낸다. */
    private String validEntryToken() throws Exception {
        return entryToken(QUEUE_SECRET, USER_ID, myScopes(), Instant.now().plusSeconds(600));
    }

    private static String scope(long scheduleId) {
        return "schedule:" + scheduleId;
    }

    private List<String> myScopes() {
        return List.of(scope(scheduleId + 100_000), scope(scheduleId));
    }

    private WebTestClient.ResponseSpec getSeats(String entryToken) throws Exception {
        return getSeats(String.valueOf(scheduleId), entryToken);
    }

    private WebTestClient.ResponseSpec getSeats(String scheduleIdInPath, String entryToken) throws Exception {
        WebTestClient.RequestHeadersSpec<?> request = client.get()
                .uri("/api/seats/schedules/{scheduleId}", scheduleIdInPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken());
        if (entryToken != null) {
            request = request.header(QueueTokenFilter.QUEUE_TOKEN_HEADER, entryToken);
        }
        return request.exchange();
    }

    private void expectInvalid(WebTestClient.ResponseSpec response) {
        response.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("QUEUE_TOKEN_INVALID");
    }

    @Test
    @DisplayName("올바른 입장 토큰이면 좌석 조회와 선점이 통과한다")
    void validTokenPasses() throws Exception {
        getSeats(validEntryToken()).expectStatus().isOk();
        client.post()
                .uri("/api/seats/schedules/{scheduleId}/holds", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                .header(QueueTokenFilter.QUEUE_TOKEN_HEADER, validEntryToken())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("좌석 구역 목록·layout·status도 보호 회차면 입장 토큰을 검사하고, 보호하지 않는 회차는 토큰 없이 통과한다(#289, #444)")
    void sectionsLayoutAndStatusRequireEntryToken() throws Exception {
        for (String path : List.of(
                "/api/seats/schedules/{scheduleId}/sections",
                "/api/seats/schedules/{scheduleId}/layout",
                "/api/seats/schedules/{scheduleId}/status")) {
            expectInvalid(client.get()
                    .uri(path, scheduleId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                    .exchange());
            client.get()
                    .uri(path, scheduleId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                    .header(QueueTokenFilter.QUEUE_TOKEN_HEADER, validEntryToken())
                    .exchange()
                    .expectStatus()
                    .isOk();
            client.get()
                    .uri(path, scheduleId + 1)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Test
    @DisplayName("좌석 조회를 HEAD로 보내도 GET과 같이 입장 토큰을 검사한다")
    void headIsCheckedLikeGet() throws Exception {
        for (String path : List.of(
                "/api/seats/schedules/{scheduleId}",
                "/api/seats/schedules/{scheduleId}/sections",
                "/api/seats/schedules/{scheduleId}/layout",
                "/api/seats/schedules/{scheduleId}/status")) {
            client.head()
                    .uri(path, scheduleId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            client.head()
                    .uri(path, scheduleId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                    .header(QueueTokenFilter.QUEUE_TOKEN_HEADER, validEntryToken())
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Test
    @DisplayName("토큰이 없거나, 다른 키로 서명했거나, 만료됐으면 403 QUEUE_TOKEN_INVALID")
    void missingForgedOrExpired() throws Exception {
        expectInvalid(getSeats(null));
        expectInvalid(getSeats(
                entryToken(LOGIN_SECRET, USER_ID, myScopes(), Instant.now().plusSeconds(600))));
        expectInvalid(getSeats(
                entryToken(QUEUE_SECRET, USER_ID, myScopes(), Instant.now().minusSeconds(1))));
        expectInvalid(client.post()
                .uri("/api/seats/schedules/{scheduleId}/holds", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                .exchange());
    }

    @Test
    @DisplayName("다른 회원의 토큰이거나 scope에 요청 회차가 없으면 403 QUEUE_TOKEN_INVALID")
    void otherUserOrOtherScope() throws Exception {
        expectInvalid(
                getSeats(entryToken(QUEUE_SECRET, 8L, myScopes(), Instant.now().plusSeconds(600))));
        expectInvalid(getSeats(entryToken(
                QUEUE_SECRET,
                USER_ID,
                List.of(scope(scheduleId + 1)),
                Instant.now().plusSeconds(600))));
    }

    @Test
    @DisplayName("회차 ID 앞에 0을 붙여도 같은 회차로 보고 검사한다")
    void leadingZeroIsSameSchedule() throws Exception {
        expectInvalid(getSeats("0" + scheduleId, null));
        getSeats("0" + scheduleId, validEntryToken()).expectStatus().isOk();
    }

    @Test
    @DisplayName("보호하는 room이 없거나 끝난 회차는 입장 토큰 없이 통과한다")
    void unprotectedSchedulePasses() throws Exception {
        given(protectedScopes.protection(scope(scheduleId))).willReturn(Protection.UNPROTECTED);

        getSeats(null).expectStatus().isOk();
        client.post()
                .uri("/api/seats/schedules/{scheduleId}/holds", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("보호 목록을 한 번도 받지 못했으면 토큰이 있어도 503 SERVICE_UNAVAILABLE")
    void unknownListIsUnavailable() throws Exception {
        given(protectedScopes.protection(scope(scheduleId))).willReturn(Protection.UNKNOWN);

        getSeats(validEntryToken())
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("선점 해제와 공개 잔여석은 입장 토큰 없이 그대로 동작한다")
    void unprotectedSeatPaths() throws Exception {
        client.delete()
                .uri("/api/seats/schedules/{scheduleId}/holds/{reservationId}", scheduleId, 1L)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/api/seats/schedules/{scheduleId}/remaining", scheduleId)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @TestConfiguration
    static class EchoConfig {

        @Bean
        RouterFunction<ServerResponse> echo() {
            return RouterFunctions.route(
                    path("/echo"), request -> ServerResponse.ok().build());
        }
    }
}
