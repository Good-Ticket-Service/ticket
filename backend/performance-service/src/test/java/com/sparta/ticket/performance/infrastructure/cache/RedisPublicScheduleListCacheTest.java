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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparta.ticket.performance.application.dto.PublicScheduleList;
import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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

/** 회차 목록 캐시의 직렬화·값 검증·Redis 오류와 회로 차단을 확인한다. 실제 Redis는 통합 테스트가 본다. */
class RedisPublicScheduleListCacheTest {

    private static final Duration TTL = Duration.ofMinutes(1);
    private static final Duration JITTER = Duration.ofSeconds(6);
    private static final Duration OPEN = Duration.ofSeconds(10);

    private final AtomicLong now = new AtomicLong();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meters;
    private RedisPublicScheduleListCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        meters = new SimpleMeterRegistry();
        cache = new RedisPublicScheduleListCache(
                redis, meters, TTL, new RedisCircuitBreaker(3, OPEN, now::get), () -> 6000);
    }

    private static PublicScheduleList detail() {
        return new PublicScheduleList(
                OperationStatus.ACTIVE,
                List.of(new PublicScheduleRow(
                        1L,
                        10L,
                        LocalDateTime.of(2026, 12, 1, 19, 0),
                        LocalDateTime.of(2026, 11, 10, 14, 0),
                        LocalDateTime.of(2026, 12, 1, 18, 0),
                        OperationStatus.ACTIVE,
                        SeatSetupStatus.READY,
                        false,
                        null)));
    }

    private double count(String op, String result) {
        return meters.counter("performance.schedule.list.cache", "op", op, "result", result)
                .count();
    }

    @Test
    @DisplayName("공연 상태·회차 enum·시각·boolean·null 취소 시각과 빈 목록을 그대로 복원한다")
    void roundTrip() {
        for (PublicScheduleList original :
                List.of(detail(), new PublicScheduleList(OperationStatus.CANCELED, List.of()))) {
            ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
            cache.put(10L, original);
            verify(values, atLeastOnce())
                    .set(eq("performance:schedule:list:v1:10"), stored.capture(), any(Duration.class));
            when(values.get("performance:schedule:list:v1:10")).thenReturn(stored.getValue());

            assertThat(cache.find(10L)).contains(original);
        }
    }

    @Test
    @DisplayName("TTL은 기본 TTL에 0–jitter를 더한 값이다")
    void ttlWithJitter() {
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        for (long jitterMillis : new long[] {0, 6000}) {
            new RedisPublicScheduleListCache(
                            redis, meters, TTL, new RedisCircuitBreaker(3, OPEN, now::get), () -> jitterMillis)
                    .put(10L, detail());
        }
        verify(values, times(2)).set(anyString(), anyString(), ttl.capture());
        assertThat(ttl.getAllValues()).containsExactly(TTL, TTL.plus(JITTER));
    }

    @Test
    @DisplayName("각 필수 필드 누락·null, 다른 공연 행은 미적중이고 nullable 취소 시각은 허용한다")
    void missingRequiredRowFields() throws Exception {
        cache.put(10L, detail());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), stored.capture(), any(Duration.class));
        ObjectMapper mapper = new ObjectMapper();
        for (String field : List.of(
                "id",
                "performanceId",
                "startAt",
                "bookingOpenAt",
                "bookingCloseAt",
                "operationStatus",
                "seatSetupStatus",
                "salesSuspended")) {
            for (boolean absent : new boolean[] {true, false}) {
                ObjectNode data = (ObjectNode) mapper.readTree(stored.getValue());
                ObjectNode row = (ObjectNode) data.path("schedules").get(0);
                if (absent) {
                    row.remove(field);
                } else {
                    row.putNull(field);
                }
                when(values.get("performance:schedule:list:v1:10")).thenReturn(data.toString());
                assertThat(cache.find(10L))
                        .as("field=%s absent=%s", field, absent)
                        .isEmpty();
            }
        }
        when(values.get("performance:schedule:list:v1:11")).thenReturn(stored.getValue());
        assertThat(cache.find(11L)).isEmpty();
        assertThat(count("get", "hit")).isZero();
        assertThat(count("get", "invalid")).isEqualTo(17);
    }

    @Test
    @DisplayName("Redis 실패는 예외 없이 미적중·저장 생략·삭제 생략으로 끝난다")
    void failuresDoNotThrow() {
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        doThrow(new QueryTimeoutException("timeout")).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(cache.find(10L)).isEmpty();
        assertThatCode(() -> cache.put(10L, detail())).doesNotThrowAnyException();
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
        for (String value : List.of("null", "{}", "{\"performanceStatus\":\"ACTIVE\",\"schedules\":null}")) {
            doReturn(value).when(values).get("performance:schedule:list:v1:10");
            assertThat(cache.find(10L)).as(value).isEmpty();
        }
        verify(redis, times(3)).delete("performance:schedule:list:v1:10");
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
        verify(redis, times(5)).delete("performance:schedule:list:v1:10");
        assertThat(count("get", "invalid")).isEqualTo(5);
        assertThat(count("get", "skipped")).isZero();
    }
}
