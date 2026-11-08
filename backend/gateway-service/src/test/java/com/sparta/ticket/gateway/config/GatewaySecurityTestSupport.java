package com.sparta.ticket.gateway.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.gateway.auth.UserLookup;
import com.sparta.ticket.gateway.auth.UserSnapshot;
import com.sparta.ticket.gateway.infrastructure.client.UserServiceClient;
import com.sparta.ticket.gateway.queue.ProtectedScopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Security 테스트 공통 준비. 실제 Security 체인과 라우팅을 태운다. 모든 /api/** 요청은 테스트 echo 핸들러로 전달되고, echo는 받은 X-User-* 헤더를 돌려준다.
 * 하위 테스트가 같은 설정·대역을 쓰므로 Spring 컨텍스트를 한 번만 띄운다.
 */
@Import(GatewaySecurityTestSupport.EchoConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "jwt.secret=" + GatewaySecurityTestSupport.SECRET,
            "queue.token-secret=" + GatewaySecurityTestSupport.QUEUE_SECRET,
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
abstract class GatewaySecurityTestSupport {

    static final String SECRET = "test-only-jwt-secret-key-0123456789abcdef";
    static final String QUEUE_SECRET = "test-only-queue-token-secret-0123456789abcdef";
    static final Long USER_ID = 1L;
    static final UserSnapshot STORED_USER = new UserSnapshot(USER_ID, "hong@ticket.com", "USER");

    @Autowired
    protected WebTestClient client;

    @org.springframework.boot.test.web.server.LocalServerPort
    protected int port;

    @MockitoBean
    protected UserLookup userLookup;

    @MockitoBean
    protected UserServiceClient userServiceClient;

    /** 기동 때 reservation-service에서 보호 목록을 받지 않게 대역으로 둔다. 입장 토큰 검사는 QueueTokenSecurityTest에서 본다. */
    @MockitoBean
    protected ProtectedScopes protectedScopes;

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

    static String token(String secret, String role, Instant expiresAt) throws Exception {
        return sign(
                secret,
                new JWTClaimsSet.Builder()
                        .subject(USER_ID.toString())
                        .claim("role", role)
                        .issueTime(Date.from(expiresAt.minusSeconds(3600)))
                        .expirationTime(Date.from(expiresAt))
                        .build());
    }

    static String sign(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static String validToken(String role) throws Exception {
        return token(SECRET, role, Instant.now().plusSeconds(3600));
    }
}
