package com.sparta.ticket.gateway.queue;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * performance-service의 공개 회차 상세(GET /api/schedules/{scheduleId})로 회차가 속한 공연을 알아낸다.
 * 입장 토큰은 공연 단위인데 좌석 경로에는 회차만 있어서 필요하다. 공개 API라 응답이 ApiResponse로 감싸져 있다.
 */
@RequiredArgsConstructor
public class PerformanceServiceClient {

    private final WebClient webClient;
    private final Duration timeout;

    /** 회차의 공연 ID. 회차가 없거나 공개되지 않았으면(404) 빈 Optional이다. 그 밖의 실패는 에러로 넘긴다. */
    public Mono<Optional<Long>> fetchPerformanceId(Long scheduleId) {
        return webClient
                .get()
                .uri("/api/schedules/{scheduleId}", scheduleId)
                .retrieve()
                .bodyToMono(ScheduleResponse.class)
                .map(response -> Optional.of(response.data().performanceId()))
                // 연결·응답 타임아웃과 별개로, 인스턴스 선택부터 본문 해석까지 전체에 상한을 둔다
                .timeout(timeout)
                .onErrorResume(
                        WebClientResponseException.class,
                        e -> e.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.just(Optional.empty()) : Mono.error(e));
    }

    /** 공개 회차 상세 응답에서 쓰는 필드만 읽는다(docs/api.md P19). */
    record ScheduleResponse(Schedule data) {

        record Schedule(Long performanceId) {}
    }
}
