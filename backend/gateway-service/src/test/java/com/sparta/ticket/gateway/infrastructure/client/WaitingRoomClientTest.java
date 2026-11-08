package com.sparta.ticket.gateway.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.gateway.infrastructure.client.WaitingRoomClient.ProtectedRoom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

class WaitingRoomClientTest {

    private static WebClient responding(HttpStatus status, String body) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("감싸지 않은 내부 목록에서 room별 scope와 closeAt(+09:00)을 읽는다")
    void readsRooms() {
        String body = """
                {"rooms":[
                  {"scopes":["schedule:1","schedule:2"],"closeAt":"2026-12-01T21:00:00+09:00"},
                  {"scopes":["schedule:3"],"closeAt":"2026-12-02T20:30:00+09:00"}]}
                """;

        assertThat(new WaitingRoomClient(responding(HttpStatus.OK, body), Duration.ofSeconds(1))
                        .fetchProtectedRooms()
                        .block())
                .containsExactly(
                        new ProtectedRoom(List.of("schedule:1", "schedule:2"), Instant.parse("2026-12-01T12:00:00Z")),
                        new ProtectedRoom(List.of("schedule:3"), Instant.parse("2026-12-02T11:30:00Z")));
    }

    @Test
    @DisplayName("보호 중인 room이 없으면 빈 목록이다")
    void emptyRooms() {
        assertThat(new WaitingRoomClient(responding(HttpStatus.OK, "{\"rooms\":[]}"), Duration.ofSeconds(1))
                        .fetchProtectedRooms()
                        .block())
                .isEmpty();
    }

    @Test
    @DisplayName("실패 응답은 에러로 넘긴다")
    void failureIsError() {
        WaitingRoomClient client =
                new WaitingRoomClient(responding(HttpStatus.SERVICE_UNAVAILABLE, "{}"), Duration.ofSeconds(1));

        StepVerifier.create(client.fetchProtectedRooms())
                .expectError(WebClientResponseException.class)
                .verify();
    }

    @Test
    @DisplayName("reservation-service가 응답하지 않으면 제한 시간 뒤 실패한다")
    void timesOut() {
        WebClient hanging =
                WebClient.builder().exchangeFunction(request -> Mono.never()).build();

        StepVerifier.create(new WaitingRoomClient(hanging, Duration.ofMillis(100)).fetchProtectedRooms())
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(2));
    }
}
