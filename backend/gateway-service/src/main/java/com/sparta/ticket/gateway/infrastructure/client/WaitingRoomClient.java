package com.sparta.ticket.gateway.infrastructure.client;

import com.sparta.ticket.gateway.queue.ProtectedRoom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * waiting-room-service의 보호 대상 목록(GET /internal/waiting-rooms/scopes) 호출.
 * 내부 API라 응답이 ApiResponse로 감싸져 있지 않다. 실패는 모두 에러로 넘기고, 마지막 목록을 계속 쓸지는 ProtectedScopes가 정한다.
 */
@RequiredArgsConstructor
public class WaitingRoomClient {

    private final WebClient webClient;
    private final Duration timeout;

    /** closeAt이 지나지 않은 room 전부(오픈 전 포함). */
    public Mono<List<ProtectedRoom>> fetchProtectedRooms() {
        return webClient
                .get()
                .uri("/internal/waiting-rooms/scopes")
                .retrieve()
                .bodyToMono(Response.class)
                .map(response -> response.rooms().stream()
                        .map(room ->
                                new ProtectedRoom(room.scopes(), room.closeAt().toInstant()))
                        .toList())
                // 연결·응답 타임아웃과 별개로, 인스턴스 선택부터 본문 해석까지 전체에 상한을 둔다
                .timeout(timeout);
    }

    /** 내부 목록 응답에서 쓰는 필드만 읽는다. closeAt은 +09:00 오프셋 문자열이다. */
    record Response(List<Room> rooms) {

        record Room(List<String> scopes, OffsetDateTime closeAt) {}
    }
}
