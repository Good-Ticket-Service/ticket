package com.sparta.ticket.user.infrastructure.security;

import com.sparta.ticket.user.application.TokenIssuer;
import com.sparta.ticket.user.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Note: HS256 대칭키 JWT 발급. 같은 secret을 가진 Gateway가 서명을 검증한다.
 * 토큰에는 식별과 인가에 필요한 최소 정보(sub=userId, username, role)만 넣는다. payload는 서명만 될 뿐 암호화되지 않으므로
 * 비밀번호나 개인정보를 넣지 않는다.
 */
@Component
public class JwtProvider implements TokenIssuer {

    private final SecretKey key;
    private final long expirationSeconds;
    private final Clock clock;

    public JwtProvider(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = properties.expirationSeconds();
        this.clock = clock;
    }

    @Override
    public String issue(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public long expirationSeconds() {
        return expirationSeconds;
    }
}
