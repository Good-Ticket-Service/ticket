package com.sparta.ticket.gateway.security;

import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.gateway.support.ErrorResponseWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity.CsrfSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.FormLoginSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.HttpBasicSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.LogoutSpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedHandler;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Gateway 인증 구성. 단계별 담당:
 * 1~2단계 UserHeaderSanitizingFilter(Security 앞) / 3단계 securityMatcher(화이트리스트 제외) /
 * 4단계 oauth2ResourceServer + ReactiveJwtDecoder / 5단계 UserVerifyingJwtConverter / 6단계 UserHeaderInjectionFilter.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Note: Gateway는 토큰만 보는 무상태 API 서버라서 세션, CSRF, 폼 로그인, Basic 인증을 모두 끈다.
     * 화이트리스트는 permitAll이 아니라 securityMatcher에서 아예 제외한다. permitAll이면 만료된 토큰을 붙인 로그인 요청도
     * 토큰 검증에 걸려 401이 되기 때문이다.
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            AuthProperties properties,
            UserLookup userLookup,
            ErrorResponseWriter errorWriter) {
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(errorWriter);
        return http.securityMatcher(new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(
                        properties.whitelist().toArray(String[]::new))))
                .csrf(CsrfSpec::disable)
                .formLogin(FormLoginSpec::disable)
                .httpBasic(HttpBasicSpec::disable)
                .logout(LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(new UserVerifyingJwtConverter(userLookup)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(
                                (exchange, denied) -> errorWriter.write(exchange, CommonErrorCode.FORBIDDEN)))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new UserHeaderInjectionFilter(), SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }

    /**
     * user-service가 같은 secret으로 서명한 HS256 토큰의 서명, 만료, 필수 claim을 검증한다.
     * Note: 기본 검증기는 exp가 "있을 때만" 만료를 검사하고, 만료 후 60초까지 허용한다. exp가 빠진 토큰은 영원히 유효해지므로
     * 필수 claim(sub, email, role, iat, exp)의 존재를 직접 검사하고, 같은 호스트의 컨테이너끼리라 허용 오차는 0으로 둔다.
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties properties) {
        SecretKeySpec key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                required(JwtClaimNames.SUB),
                required("email"),
                required("role"),
                required(JwtClaimNames.IAT),
                required(JwtClaimNames.EXP)));
        return decoder;
    }

    private static JwtClaimValidator<Object> required(String claim) {
        return new JwtClaimValidator<>(claim, Objects::nonNull);
    }

    /** 방화벽이 거부한 요청(경로 우회 시도 등)도 공통 에러 형식의 400으로 응답한다. 기본값은 본문 없는 400이다. */
    @Bean
    public ServerExchangeRejectedHandler serverExchangeRejectedHandler(ErrorResponseWriter errorWriter) {
        return (exchange, rejected) -> errorWriter.write(exchange, CommonErrorCode.INVALID_REQUEST);
    }
}
