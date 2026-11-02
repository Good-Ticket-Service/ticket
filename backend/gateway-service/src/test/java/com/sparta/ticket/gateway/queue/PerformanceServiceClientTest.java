package com.sparta.ticket.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PerformanceServiceClientTest {

    private static WebClient responding(HttpStatus status, String body) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("공개 회차 상세(ApiResponse로 감싼 P19)에서 공연 ID를 읽고, 쓰지 않는 필드는 무시한다")
    void readsPerformanceId() {
        String body = """
                {"status":200,"message":"SUCCESS","data":{"id":42,"performanceId":3,
                 "startAt":"2026-12-01T19:00:00","salesStatus":"ON_SALE","canceledAt":null}}
                """;

        assertThat(new PerformanceServiceClient(responding(HttpStatus.OK, body), Duration.ofSeconds(1))
                        .fetchPerformanceId(42L)
                        .block())
                .contains(3L);
    }

    @Test
    @DisplayName("회차가 없거나 공개되지 않아 404면 빈 값이다")
    void notFoundIsEmpty() {
        String body = """
                {"status":404,"code":"SCHEDULE_NOT_FOUND","message":"회차를 찾을 수 없습니다."}
                """;

        assertThat(new PerformanceServiceClient(responding(HttpStatus.NOT_FOUND, body), Duration.ofSeconds(1))
                        .fetchPerformanceId(42L)
                        .block())
                .isEmpty();
    }

    @Test
    @DisplayName("404가 아닌 실패(503 등)는 에러로 넘긴다")
    void otherFailureIsError() {
        PerformanceServiceClient client =
                new PerformanceServiceClient(responding(HttpStatus.SERVICE_UNAVAILABLE, "{}"), Duration.ofSeconds(1));

        StepVerifier.create(client.fetchPerformanceId(42L))
                .expectError(WebClientResponseException.class)
                .verify();
    }

    @Test
    @DisplayName("performance-service가 응답하지 않으면 무한 대기하지 않고 제한 시간 뒤 실패한다")
    void timesOut() {
        WebClient hanging =
                WebClient.builder().exchangeFunction(request -> Mono.never()).build();

        StepVerifier.create(new PerformanceServiceClient(hanging, Duration.ofMillis(100)).fetchPerformanceId(42L))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(2));
    }
}
