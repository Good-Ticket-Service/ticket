package com.sparta.ticket.reservation.queue.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtEntryTokenIssuerTest {

    private static final String SECRET = "test-only-queue-token-secret-0123456789abcdef";
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-02T11:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-02T11:10:00Z");

    private final JwtEntryTokenIssuer issuer = new JwtEntryTokenIssuer(new QueueTokenProperties(SECRET));

    private static Claims parse(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // 만료 검사는 Gateway 몫이라 여기서는 서명과 claim만 본다. 테스트 시각이 만료 뒤여도 읽히도록 시계를 입장 시각에 둔다
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(ADMITTED_AT))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Test
    @DisplayName("회원(sub), 공연, 입장 시각(iat), 만료 시각(exp)을 같은 키로 서명한다")
    void claims() {
        Claims claims = parse(issuer.issue(7L, 1L, ADMITTED_AT, EXPIRES_AT), SECRET);

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get(JwtEntryTokenIssuer.PERFORMANCE_ID, Long.class)).isEqualTo(1L);
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(ADMITTED_AT);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("같은 입장이면 몇 번 발급해도 같은 토큰이다")
    void sameAdmissionSameToken() {
        assertThat(issuer.issue(7L, 1L, ADMITTED_AT, EXPIRES_AT))
                .isEqualTo(issuer.issue(7L, 1L, ADMITTED_AT, EXPIRES_AT));
    }

    @Test
    @DisplayName("다른 키(예: 로그인 JWT 키)로는 서명 검증에 실패한다")
    void otherKeyFails() {
        String token = issuer.issue(7L, 1L, ADMITTED_AT, EXPIRES_AT);

        assertThatThrownBy(() -> parse(token, "other-secret-for-login-jwt-0123456789abcdef"))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("키가 32바이트보다 짧으면 만들 때 실패한다(기동이 멈춘다)")
    void weakKey() {
        assertThatThrownBy(() -> new JwtEntryTokenIssuer(new QueueTokenProperties("short")))
                .isInstanceOf(WeakKeyException.class);
    }
}
