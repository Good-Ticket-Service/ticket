package com.sparta.ticket.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.user.UserFixture;
import com.sparta.ticket.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String SECRET = "test-only-jwt-secret-key-0123456789abcdef";

    @Test
    @DisplayName("sub=userId, role만 담고 60분 뒤 만료되는 HS256 토큰을 발급한다")
    void issuesToken() {
        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, ZoneId.systemDefault());
        JwtProvider provider = new JwtProvider(new JwtProperties(SECRET, 3600), clock);
        User user = UserFixture.user("hong@ticket.com", "encoded");

        String token = provider.issue(user);

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(now))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims).doesNotContainKeys("email", "username");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.getExpiration().toInstant().getEpochSecond()
                        - claims.getIssuedAt().toInstant().getEpochSecond())
                .isEqualTo(3600);
    }
}
