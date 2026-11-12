package com.sparta.ticket.performance.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 상세 캐시 어댑터의 실패 처리와 건너뛰기를 가짜 Redis로 확인한다. 실제 Redis 동작은 통합 테스트가 본다. */
class RedisPublicPerformanceDetailCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration JITTER = Duration.ofSeconds(60);
    private static final Duration OPEN = Duration.ofSeconds(10);

    private final AtomicLong now = new AtomicLong();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meters;
    private RedisPublicPerformanceDetailCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        meters = new SimpleMeterRegistry();
        cache = new RedisPublicPerformanceDetailCache(
                redis, meters, TTL, JITTER, new RedisCircuitBreaker(3, OPEN, now::get));
    }

    private static PublicPerformanceDetail detail(
            PerformancePeriod period, List<PublicPerformanceDetail.GradePrice> prices) {
        return new PublicPerformanceDetail(
                10L,
                new VenueSummary(1L, "서울 공연장", "서울특별시 중구 예시로 10", "서울"),
                "뮤지컬 Alpha",
                Genre.MUSICAL,
                AgeRating.values()[0],
                null,
                150,
                null,
                "설명",
                null,
                OperationStatus.ACTIVE,
                LocalDateTime.of(2026, 10, 20, 10, 0),
                period,
                LocalDateTime.of(2026, 10, 19, 9, 0),
                LocalDateTime.of(2026, 10, 20, 10, 0, 0, 123_000_000),
                prices);
    }

    private double count(String op, String result) {
        return meters.counter("performance.detail.cache", "op", op, "result", result)
                .count();
    }

    @Test
    @DisplayName("저장한 값을 같은 DTO로 복원한다. 기간·가격이 있을 때와 없을 때 모두")
    void roundTrip() {
        for (PublicPerformanceDetail original : List.of(
                detail(
                        new PerformancePeriod(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2)),
                        List.of(new PublicPerformanceDetail.GradePrice(1L, "VIP", 150000))),
                detail(null, List.of()))) {
            ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
            cache.put(original);
            verify(values, atLeastOnce()).set(eq("performance:detail:v1:10"), stored.capture(), any(Duration.class));
            when(values.get("performance:detail:v1:10")).thenReturn(stored.getValue());

            assertThat(cache.find(10L)).contains(original);
        }
    }

    @Test
    @DisplayName("TTL은 기본 TTL에 0–jitter를 더한 값이다")
    void ttlWithJitter() {
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        for (int i = 0; i < 20; i++) {
            cache.put(detail(null, List.of()));
        }
        verify(values, times(20)).set(anyString(), anyString(), ttl.capture());
        assertThat(ttl.getAllValues()).allSatisfy(value -> assertThat(value).isBetween(TTL, TTL.plus(JITTER)));
    }

    @Test
    @DisplayName("Redis 실패는 예외 없이 미적중·저장 생략·삭제 생략으로 끝난다")
    void failuresDoNotThrow() {
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        doThrow(new QueryTimeoutException("timeout")).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(cache.find(10L)).isEmpty();
        assertThatCode(() -> cache.put(detail(null, List.of()))).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict(10L)).doesNotThrowAnyException();
        assertThat(count("get", "error")).isEqualTo(1);
        assertThat(count("put", "error")).isEqualTo(1);
        assertThat(count("evict", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("연속 실패가 기준에 닿으면 건너뛰는 시간 동안 Redis를 부르지 않고, 지나면 다시 부른다")
    void skipsRedisWhileOpen() {
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        for (int i = 0; i < 3; i++) {
            cache.find(10L);
        }
        verify(values, times(3)).get(anyString());

        cache.find(10L);
        cache.evict(10L);
        verify(values, times(3)).get(anyString());
        verify(redis, never()).delete(anyString());
        assertThat(count("get", "skipped")).isEqualTo(1);
        assertThat(count("evict", "skipped")).isEqualTo(1);

        now.addAndGet(OPEN.toNanos());
        doReturn(null).when(values).get(anyString());
        assertThat(cache.find(10L)).isEmpty();
        verify(values, times(4)).get(anyString());
        assertThat(count("get", "miss")).isEqualTo(1);
    }

    @Test
    @DisplayName("건너뛰는 시간 뒤 다시 실패하면 바로 다시 건너뛴다")
    void reopensOnFailureAfterOpen() {
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        for (int i = 0; i < 3; i++) {
            cache.find(10L);
        }
        now.addAndGet(OPEN.toNanos());
        cache.find(10L);
        cache.find(10L);
        verify(values, times(4)).get(anyString());
    }

    @Test
    @DisplayName("JSON은 맞아도 쓸 수 없는 값(null, {}, 다른 공연의 값)은 지우고 미적중으로 본다")
    void unusableValueIsDeleted() {
        String otherPerformance =
                "{\"id\":11,\"venue\":{\"id\":1,\"name\":\"서울 공연장\",\"address\":\"주소\",\"region\":\"서울\"},"
                        + "\"title\":\"다른 공연\",\"genre\":\"PLAY\",\"runningTime\":90,\"operationStatus\":\"ACTIVE\","
                        + "\"createdAt\":\"2026-10-19T09:00:00\",\"updatedAt\":\"2026-10-19T09:00:00\",\"gradePrices\":[]}";
        for (String value : List.of("null", "{}", otherPerformance)) {
            doReturn(value).when(values).get("performance:detail:v1:10");
            assertThat(cache.find(10L)).as(value).isEmpty();
        }
        verify(redis, times(3)).delete("performance:detail:v1:10");
        assertThat(count("get", "invalid")).isEqualTo(3);
        assertThat(count("get", "hit")).isZero();
    }

    @Test
    @DisplayName("읽지 못하는 값은 지우고 미적중으로 본다. Redis 장애로 세지 않는다")
    void invalidValueIsDeleted() {
        when(values.get(anyString())).thenReturn("{not-json");
        for (int i = 0; i < 5; i++) {
            assertThat(cache.find(10L)).isEmpty();
        }
        verify(redis, times(5)).delete("performance:detail:v1:10");
        assertThat(count("get", "invalid")).isEqualTo(5);
        assertThat(count("get", "skipped")).isZero();
    }
}
