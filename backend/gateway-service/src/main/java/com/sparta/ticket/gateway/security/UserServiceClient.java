package com.sparta.ticket.gateway.security;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * user-service 내부 API(GET /internal/users/{userId}) 호출. Gateway는 WebFlux 기반이라 블로킹 Feign 대신 WebClient를 쓴다.
 * 내부 API는 DTO를 감싸지 않고 그대로 반환한다.
 */
@RequiredArgsConstructor
public class UserServiceClient {

    private final WebClient webClient;
    private final Duration timeout;

    public Mono<Optional<UserSnapshot>> fetch(UUID userId) {
        return webClient.get()
                .uri("/internal/users/{userId}", userId)
                .retrieve()
                .bodyToMono(UserSnapshot.class)
                .map(Optional::of)
                // 연결·응답 타임아웃과 별개로, 인스턴스 선택부터 본문 해석까지 전체에 상한을 둔다
                .timeout(timeout)
                .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode() == HttpStatus.NOT_FOUND
                        ? Mono.just(Optional.empty())
                        : Mono.error(e));
    }
}
