package com.sparta.ticket.gateway.security;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sparta.ticket.gateway.queue.PerformanceServiceClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
 * 대기열을 켠 채 실제 Security 체인과 라우팅을 태워 좌석 경로의 입장 토큰 검사를 확인한다. 통과한 요청은 테스트 echo 핸들러가 200으로 받는다.
 * performance-service 호출만 가짜로 두고 회차 → 공연 캐시는 실제 구현을 쓴다. 캐시가 테스트끼리 남으므로 테스트마다 다른 회차 ID를 쓴다.
 * 대기열이 꺼진 경우는 GatewaySecurityTest(기본 설정 off)에서 본다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "jwt.secret=" + QueueTokenSecurityTest.LOGIN_SECRET,
            "queue.enabled=true",
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
    private static final long PERFORMANCE_ID = 3L;
    private static final AtomicLong SCHEDULE_IDS = new AtomicLong(1000);

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private UserLookup userLookup;

    @MockitoBean
    private UserServiceClient userServiceClient;

    @MockitoBean
    private PerformanceServiceClient performanceServiceClient;

    private long scheduleId;

    @BeforeEach
    void setUp() {
        scheduleId = SCHEDULE_IDS.incrementAndGet();
        given(userLookup.find(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "hong@ticket.com", "USER"))));
        given(performanceServiceClient.fetchPerformanceId(anyLong()))
                .willReturn(Mono.just(Optional.of(PERFORMANCE_ID)));
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

    /** reservation-service 대기열이 발급하는 모양의 입장 토큰. */
    private static String entryToken(String secret, long userId, long performanceId, Instant expiresAt)
            throws Exception {
        return sign(
                secret,
                new JWTClaimsSet.Builder()
                        .subject(String.valueOf(userId))
                        .claim("performanceId", performanceId)
                        .issueTime(Date.from(expiresAt.minusSeconds(600)))
                        .expirationTime(Date.from(expiresAt))
                        .build());
    }

    private static String validEntryToken() throws Exception {
        return entryToken(QUEUE_SECRET, USER_ID, PERFORMANCE_ID, Instant.now().plusSeconds(600));
    }

    private WebTestClient.ResponseSpec getSeats(String entryToken) throws Exception {
        WebTestClient.RequestHeadersSpec<?> request = client.get()
                .uri("/api/seats/schedules/{scheduleId}", scheduleId)
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
    @DisplayName("토큰이 없거나, 다른 키로 서명했거나, 만료됐으면 403 QUEUE_TOKEN_INVALID")
    void missingForgedOrExpired() throws Exception {
        expectInvalid(getSeats(null));
        expectInvalid(getSeats(
                entryToken(LOGIN_SECRET, USER_ID, PERFORMANCE_ID, Instant.now().plusSeconds(600))));
        expectInvalid(getSeats(
                entryToken(QUEUE_SECRET, USER_ID, PERFORMANCE_ID, Instant.now().minusSeconds(1))));
        expectInvalid(client.post()
                .uri("/api/seats/schedules/{scheduleId}/holds", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken())
                .exchange());
    }

    @Test
    @DisplayName("다른 회원의 토큰이거나 다른 공연의 토큰이면 403 QUEUE_TOKEN_INVALID")
    void otherUserOrOtherPerformance() throws Exception {
        expectInvalid(getSeats(
                entryToken(QUEUE_SECRET, 8L, PERFORMANCE_ID, Instant.now().plusSeconds(600))));
        expectInvalid(
                getSeats(entryToken(QUEUE_SECRET, USER_ID, 4L, Instant.now().plusSeconds(600))));
    }

    @Test
    @DisplayName("회차가 없으면(공연 서비스 404) 403 QUEUE_TOKEN_INVALID")
    void unknownSchedule() throws Exception {
        given(performanceServiceClient.fetchPerformanceId(scheduleId)).willReturn(Mono.just(Optional.empty()));

        expectInvalid(getSeats(validEntryToken()));
    }

    @Test
    @DisplayName("회차를 확인하지 못하면 통과시키지 않고 503 SERVICE_UNAVAILABLE")
    void scheduleLookupFails() throws Exception {
        given(performanceServiceClient.fetchPerformanceId(scheduleId))
                .willReturn(Mono.error(new IllegalStateException("performance-service 503")));

        getSeats(validEntryToken())
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("같은 회차를 다시 검사할 때는 공연 서비스를 다시 부르지 않는다")
    void cachesSchedule() throws Exception {
        getSeats(validEntryToken()).expectStatus().isOk();
        getSeats(validEntryToken()).expectStatus().isOk();

        verify(performanceServiceClient, times(1)).fetchPerformanceId(scheduleId);
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
