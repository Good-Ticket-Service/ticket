package com.sparta.ticket.gateway.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class QueueTokenVerifierTest {

    private static final String SECRET = "test-only-queue-token-secret-0123456789abcdef";

    private final QueueTokenVerifier verifier = new QueueTokenVerifier(SECRET);

    /** reservation-service가 발급하는 모양(sub 문자열, roomId 숫자, scopes 문자열 배열, iat, exp)으로 서명한다. */
    private static String sign(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder claims(Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .subject("7")
                .claim(QueueTokenVerifier.ROOM_ID, 3L)
                .claim(QueueTokenVerifier.SCOPES, List.of("schedule:12", "schedule:13"))
                .issueTime(Date.from(expiresAt.minusSeconds(600)))
                .expirationTime(Date.from(expiresAt));
    }

    @Test
    @DisplayName("같은 키로 서명한 만료 전 토큰이면 회원·room·scope를 꺼낸다")
    void valid() throws Exception {
        String token = sign(SECRET, claims(Instant.now().plusSeconds(600)).build());

        StepVerifier.create(verifier.verify(token))
                .expectNext(new EntryToken(7, 3, List.of("schedule:12", "schedule:13")))
                .verifyComplete();
    }

    @Test
    @DisplayName("만료됐거나, 다른 키(예: 로그인 JWT 키)로 서명했거나, 형식이 깨졌으면 비어 있다")
    void invalid() throws Exception {
        String expired = sign(SECRET, claims(Instant.now().minusSeconds(1)).build());
        String otherKey = sign(
                "test-only-jwt-secret-key-0123456789abcdef",
                claims(Instant.now().plusSeconds(600)).build());

        StepVerifier.create(verifier.verify(expired)).verifyComplete();
        StepVerifier.create(verifier.verify(otherKey)).verifyComplete();
        StepVerifier.create(verifier.verify("not-a-jwt")).verifyComplete();
    }

    @Test
    @DisplayName("room·scopes가 없거나 형식이 틀리거나, 회원이 숫자가 아니면 비어 있다")
    void missingOrMalformedClaims() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(600);
        String noRoom = sign(
                SECRET,
                claims(expiresAt).claim(QueueTokenVerifier.ROOM_ID, null).build());
        String noScopes = sign(
                SECRET, claims(expiresAt).claim(QueueTokenVerifier.SCOPES, null).build());
        String textRoom = sign(
                SECRET, claims(expiresAt).claim(QueueTokenVerifier.ROOM_ID, "3").build());
        String textScopes = sign(
                SECRET,
                claims(expiresAt)
                        .claim(QueueTokenVerifier.SCOPES, "schedule:12")
                        .build());
        String numberScopes = sign(
                SECRET,
                claims(expiresAt).claim(QueueTokenVerifier.SCOPES, List.of(12)).build());
        String textSubject = sign(SECRET, claims(expiresAt).subject("hong").build());

        for (String token : List.of(noRoom, noScopes, textRoom, textScopes, numberScopes, textSubject)) {
            StepVerifier.create(verifier.verify(token)).verifyComplete();
        }
    }

    @Test
    @DisplayName("키가 32바이트보다 짧거나 없으면 만들 때 실패한다(기동이 멈춘다)")
    void weakKey() {
        assertThatThrownBy(() -> new QueueTokenVerifier("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new QueueTokenVerifier(null)).isInstanceOf(NullPointerException.class);
    }
}
