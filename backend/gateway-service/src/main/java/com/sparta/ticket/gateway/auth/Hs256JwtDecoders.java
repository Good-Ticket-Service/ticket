package com.sparta.ticket.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

/**
 * HS256 JWT 디코더를 만든다. 로그인 토큰(SecurityConfig)과 입장 토큰(QueueTokenVerifier)이 키만 다르고 같은 규칙을 쓴다.
 *
 * Note: 기본 검증기는 exp가 "있을 때만" 만료를 검사하고, 만료 후 60초까지 허용한다. exp가 빠진 토큰은 영원히 유효해지므로
 * 필수 claim(sub, iat, exp와 토큰별 claim)의 존재를 직접 검사하고, 같은 호스트의 컨테이너끼리라 허용 오차는 0으로 둔다.
 */
public final class Hs256JwtDecoders {

    private Hs256JwtDecoders() {}

    /** @param claims sub·iat·exp 말고 반드시 있어야 하는 claim(예: 로그인 토큰의 role) */
    public static NimbusReactiveJwtDecoder create(String secret, String... claims) {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(Duration.ZERO));
        validators.add(required(JwtClaimNames.SUB));
        for (String claim : claims) {
            validators.add(required(claim));
        }
        validators.add(required(JwtClaimNames.IAT));
        validators.add(required(JwtClaimNames.EXP));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static JwtClaimValidator<Object> required(String claim) {
        return new JwtClaimValidator<>(claim, Objects::nonNull);
    }
}
