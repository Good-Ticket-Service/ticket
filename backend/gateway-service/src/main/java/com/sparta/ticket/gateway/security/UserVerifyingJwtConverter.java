package com.sparta.ticket.gateway.security;

import com.sparta.ticket.common.exception.CommonErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * 인증 5단계. 서명·만료 검증을 통과한 JWT를 받아, 저장된 role·탈퇴 여부를 user-service 조회(60초 캐시)로 다시 확인한다.
 * Note: JWT는 발급 뒤에 바뀐 권한이나 탈퇴를 모른다. 그래서 Spring Security의 JWT → Authentication 변환 지점에 재확인을
 * 끼워 넣는다. 통과하면 저장된 사용자 정보(UserSnapshot)를 principal로 담아 6단계 헤더 주입에 넘긴다.
 */
@RequiredArgsConstructor
public class UserVerifyingJwtConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final UserLookup userLookup;

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Mono.error(new GatewayAuthenticationException(CommonErrorCode.UNAUTHORIZED));
        }
        String tokenRole = jwt.getClaimAsString("role");

        return userLookup
                .find(userId)
                .onErrorMap(e -> new GatewayAuthenticationException(CommonErrorCode.SERVICE_UNAVAILABLE, e))
                .flatMap(found -> found.filter(user -> user.role().equals(tokenRole))
                        .map(user -> Mono.<AbstractAuthenticationToken>just(authenticated(user, jwt)))
                        .orElseGet(() ->
                                Mono.error(new GatewayAuthenticationException(CommonErrorCode.TOKEN_ROLE_MISMATCH))));
    }

    private UsernamePasswordAuthenticationToken authenticated(UserSnapshot user, Jwt jwt) {
        return UsernamePasswordAuthenticationToken.authenticated(
                user, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
    }
}
