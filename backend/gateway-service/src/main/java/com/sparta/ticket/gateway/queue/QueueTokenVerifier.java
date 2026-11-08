package com.sparta.ticket.gateway.queue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 입장 토큰의 서명과 만료를 확인하고 회원·room·scope를 꺼낸다. 토큰은 reservation-service 대기열이 같은 키로 발급한다(docs/api.md 대기열 절).
 *
 * Note: 로그인 JWT 디코더(SecurityConfig)와 같은 방식이지만 키가 달라 따로 둔다. ReactiveJwtDecoder 빈으로 내놓으면
 * 로그인 인증이 어느 디코더를 쓸지 모호해지므로 이 클래스 안에 감춘다. 만료 허용 오차는 로그인 토큰처럼 0이다.
 */
public class QueueTokenVerifier {

    static final String ROOM_ID = "roomId";
    static final String SCOPES = "scopes";

    /** HS256 키는 256비트 이상이어야 한다. 짧으면 기동을 멈춘다. */
    private static final int MIN_SECRET_BYTES = 32;

    private final NimbusReactiveJwtDecoder decoder;

    public QueueTokenVerifier(String secret) {
        byte[] bytes =
                Objects.requireNonNull(secret, "queue.token-secret이 없습니다").getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("queue.token-secret은 32바이트 이상이어야 합니다");
        }
        this.decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                required(JwtClaimNames.SUB),
                required(ROOM_ID),
                required(SCOPES),
                required(JwtClaimNames.IAT),
                required(JwtClaimNames.EXP)));
    }

    /** 서명·만료·필수 claim이 맞으면 토큰 내용을, 하나라도 틀리면 빈 값을 돌려준다. */
    public Mono<EntryToken> verify(String token) {
        return decoder.decode(token)
                .flatMap(QueueTokenVerifier::toEntryToken)
                .onErrorResume(JwtException.class, e -> Mono.empty());
    }

    /** roomId가 숫자가 아니거나 scopes가 문자열 배열이 아니거나 회원이 숫자가 아니면 빈 값이다. */
    private static Mono<EntryToken> toEntryToken(Jwt jwt) {
        if (!(jwt.getClaim(ROOM_ID) instanceof Number roomId)
                || !(jwt.getClaim(SCOPES) instanceof List<?> scopes)
                || !scopes.stream().allMatch(String.class::isInstance)) {
            return Mono.empty();
        }
        try {
            return Mono.just(new EntryToken(
                    Long.parseLong(jwt.getSubject()),
                    roomId.longValue(),
                    scopes.stream().map(String.class::cast).toList()));
        } catch (NumberFormatException e) {
            return Mono.empty();
        }
    }

    private static JwtClaimValidator<Object> required(String claim) {
        return new JwtClaimValidator<>(claim, Objects::nonNull);
    }
}
