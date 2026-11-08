package com.sparta.ticket.reservation.queue.infrastructure.token;

import com.sparta.ticket.reservation.queue.application.EntryTokenIssuer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Note: HS256 대칭키 JWT로 입장 토큰을 발급한다. 같은 키를 가진 Gateway가 서명·만료와 요청 URL이 scope에 드는지 검사한다.
 * Gateway는 대기열에 묻지 않고 토큰만으로 판단하므로 scope를 토큰에 싣는다. room은 생성 뒤 바뀌지 않아 토큰의 scope가 낡지 않는다.
 * 로그인 JWT와 키를 나눠, 로그인 토큰을 입장 토큰으로 내거나 그 반대로 쓰는 일을 서명 단계에서 막는다.
 * 입장 시각과 만료 시각은 Redis 입장 목록에 고정된 값이라 조회할 때마다 같은 토큰이 나오고, 조회로 만료가 늘어나지 않는다.
 */
@Component
public class JwtEntryTokenIssuer implements EntryTokenIssuer {

    static final String ROOM_ID = "roomId";
    static final String SCOPES = "scopes";

    private final SecretKey key;

    public JwtEntryTokenIssuer(QueueTokenProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(long userId, long roomId, List<String> scopes, Instant admittedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(ROOM_ID, roomId)
                .claim(SCOPES, scopes)
                .issuedAt(Date.from(admittedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
