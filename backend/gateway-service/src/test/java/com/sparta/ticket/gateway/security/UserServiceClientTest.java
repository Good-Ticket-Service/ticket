package com.sparta.ticket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class UserServiceClientTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("user-service가 응답하지 않으면 무한 대기하지 않고 제한 시간 뒤 실패한다")
    void timesOut() {
        WebClient hanging =
                WebClient.builder().exchangeFunction(request -> Mono.never()).build();
        UserServiceClient client = new UserServiceClient(hanging, Duration.ofMillis(100));

        StepVerifier.create(client.fetch(userId))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("내부 사용자 조회 응답 본문(감싸지 않은 DTO)을 그대로 읽고, 쓰지 않는 필드는 무시한다")
    void readsUnwrappedBody() {
        String body = """
                {"userId":"%s","username":"hong01","role":"MASTER","name":"홍길동"}
                """.formatted(userId);
        WebClient ok = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();

        assertThat(new UserServiceClient(ok, Duration.ofSeconds(1))
                        .fetch(userId)
                        .block())
                .contains(new UserSnapshot(userId, "hong01", "MASTER"));
    }

    @Test
    @DisplayName("내부 사용자 조회가 404면 탈퇴한 사용자로 보고 빈 값을 돌려준다")
    void notFoundIsEmpty() {
        WebClient notFound = WebClient.builder()
                .exchangeFunction(request ->
                        Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()))
                .build();

        assertThat(new UserServiceClient(notFound, Duration.ofSeconds(1))
                        .fetch(userId)
                        .block())
                .isEmpty();
    }
}
