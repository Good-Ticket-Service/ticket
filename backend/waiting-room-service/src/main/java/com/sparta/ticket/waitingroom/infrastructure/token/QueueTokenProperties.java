package com.sparta.ticket.waitingroom.infrastructure.token;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 입장 토큰 서명 설정. 키는 저장소에 두지 않고 환경 변수 QUEUE_TOKEN_SECRET으로 받는다.
 *
 * @param secret HS256 서명 키. 32바이트 이상이어야 하고, 로그인 JWT 키와 다른 값을 쓴다
 */
@Validated
@ConfigurationProperties(prefix = "waiting-room.token")
public record QueueTokenProperties(@NotBlank String secret) {}
