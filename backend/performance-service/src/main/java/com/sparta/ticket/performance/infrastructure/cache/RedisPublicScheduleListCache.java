package com.sparta.ticket.performance.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparta.ticket.performance.application.dto.PublicScheduleList;
import com.sparta.ticket.performance.application.port.PublicScheduleListCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 판매 상태 계산 전의 회차 조회값을 JSON으로 저장한다(이슈 #431).
 * Note: 적중으로 TTL을 연장하지 않는다. 삭제 실패·이전 값 재저장은 저장 시점 기준 TTL로 수용한다.
 * 상세 캐시와 명령 제한 시간은 공유하되 회로 차단 상태는 별도 인스턴스로 둔다.
 */
@Slf4j
@Component
public class RedisPublicScheduleListCache implements PublicScheduleListCache {

    static final String KEY_PREFIX = "performance:schedule:list:v1:";
    private static final String METRIC = "performance.schedule.list.cache";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final Duration ttl;
    private final RedisCircuitBreaker breaker;
    private final MeterRegistry meters;
    private final LongSupplier jitter;

    @Autowired
    public RedisPublicScheduleListCache(
            StringRedisTemplate redis,
            MeterRegistry meters,
            @Value("${performance.schedule-list-cache.ttl:1m}") Duration ttl,
            @Value("${performance.schedule-list-cache.jitter:6s}") Duration jitter,
            @Value("${performance.schedule-list-cache.failure-threshold:5}") int failureThreshold,
            @Value("${performance.schedule-list-cache.open-duration:10s}") Duration openDuration) {
        this(
                redis,
                meters,
                ttl,
                new RedisCircuitBreaker(failureThreshold, openDuration, System::nanoTime),
                () -> ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1));
    }

    RedisPublicScheduleListCache(
            StringRedisTemplate redis,
            MeterRegistry meters,
            Duration ttl,
            RedisCircuitBreaker breaker,
            LongSupplier jitterSource) {
        this.redis = redis;
        this.meters = meters;
        this.ttl = ttl;
        this.breaker = breaker;
        this.jitter = jitterSource;
    }

    static String key(Long performanceId) {
        return KEY_PREFIX + performanceId;
    }

    @Override
    public Optional<PublicScheduleList> find(Long performanceId) {
        String key = key(performanceId);
        Attempt<String> value = call("get", () -> redis.opsForValue().get(key));
        if (!value.done()) {
            return Optional.empty();
        }
        if (value.result() == null) {
            count("get", "miss");
            return Optional.empty();
        }
        PublicScheduleList detail;
        try {
            detail = json.readValue(value.result(), PublicScheduleList.class);
        } catch (JsonProcessingException e) {
            return discard(key, e.getOriginalMessage());
        }
        // JSON 문법은 맞아도 DTO로 쓸 수 없는 값(null, {}, 다른 공연의 값)은 응답 변환에서 500이 되므로 읽지 못한 값과 같이 버린다
        if (!isUsable(detail, performanceId)) {
            return discard(key, "필수 필드가 없거나 다른 공연의 값");
        }
        count("get", "hit");
        return Optional.of(detail);
    }

    /** 형식이 맞지 않는 값은 다시 읽어도 같으므로 지우고 DB에서 새로 채운다. Redis 장애가 아니라서 건너뛰기 수에 넣지 않는다. */
    private Optional<PublicScheduleList> discard(String key, String reason) {
        log.warn("회차 목록 캐시 값을 쓸 수 없어 지운다. key={}, reason={}", key, reason);
        count("get", "invalid");
        call("evict", () -> redis.delete(key));
        return Optional.empty();
    }

    /** nullable인 취소 시각은 허용하고, 상태 계산·응답에 필요한 필드와 공연 소속을 검사한다. */
    private static boolean isUsable(PublicScheduleList data, Long performanceId) {
        return data != null
                && data.performanceStatus() != null
                && data.schedules() != null
                && data.schedules().stream()
                        .allMatch(row -> row != null
                                && row.id() != null
                                && performanceId.equals(row.performanceId())
                                && row.startAt() != null
                                && row.bookingOpenAt() != null
                                && row.bookingCloseAt() != null
                                && row.operationStatus() != null
                                && row.seatSetupStatus() != null);
    }

    @Override
    public void put(Long performanceId, PublicScheduleList detail) {
        String value;
        try {
            value = json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("회차 목록 캐시 값을 만들지 못했다. performanceId={}", performanceId, e);
            count("put", "invalid");
            return;
        }
        Duration expiresIn = ttl.plusMillis(jitter.getAsLong());
        Attempt<Boolean> stored = call("put", () -> {
            redis.opsForValue().set(key(performanceId), value, expiresIn);
            return Boolean.TRUE;
        });
        if (stored.done()) {
            count("put", "ok");
        }
    }

    /**
     * Note: 삭제 실패는 등록 요청을 실패시키지 않는다. 이전 값은 TTL 안에 사라진다. 놓친 삭제를 찾을 수 있게
     * 건너뛴 경우를 포함해 경고 로그를 남긴다.
     */
    @Override
    public void evict(Long performanceId) {
        String key = key(performanceId);
        if (!call("evict", () -> redis.delete(key)).done()) {
            log.warn("회차 목록 캐시를 지우지 못했다. 이전 값은 TTL 안에 만료된다. key={}", key);
            return;
        }
        count("evict", "ok");
    }

    /** Redis 명령을 실행한다. 건너뛰는 중이거나 실패하면 done이 false다. 키가 없으면 done이 true이고 result가 null이다. */
    private <T> Attempt<T> call(String op, Supplier<T> command) {
        if (!breaker.allowRequest()) {
            count(op, "skipped");
            return Attempt.notDone();
        }
        Timer.Sample sample = Timer.start(meters);
        try {
            T result = command.get();
            breaker.recordSuccess();
            return new Attempt<>(true, result);
        } catch (RuntimeException e) {
            breaker.recordFailure(e);
            log.debug("Redis 명령 실패. op={}", op, e);
            count(op, "error");
            return Attempt.notDone();
        } finally {
            sample.stop(meters.timer(METRIC + ".command", "op", op));
        }
    }

    private void count(String op, String result) {
        meters.counter(METRIC, "op", op, "result", result).increment();
    }

    /** Redis 명령 한 번의 결과. done이 false면 Redis를 쓰지 못한 것이다. */
    private record Attempt<T>(boolean done, T result) {

        static <T> Attempt<T> notDone() {
            return new Attempt<>(false, null);
        }
    }
}
