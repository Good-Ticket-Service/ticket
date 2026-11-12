package com.sparta.ticket.performance.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.port.PublicPerformanceDetailCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 공개 공연 상세 조회 DTO를 Redis에 JSON으로 저장한다(이슈 #398). 키는 performance:detail:v1:{performanceId}다.
 * TTL은 저장할 때마다 기본 TTL(10분)에 0–jitter(60초) 사이의 무작위 시간을 더한다. 여러 키가 같은 시각에 저장돼도
 * 만료가 한꺼번에 몰리지 않게 하려는 것이다. 적중해도 TTL을 늘리지 않는다.
 * 실패는 예외로 올리지 않는다. 읽기 실패는 미적중, 저장·삭제 실패는 지표·로그만 남긴다. 장애가 이어지면 RedisCircuitBreaker가
 * 명령을 건너뛰게 한다.
 * Note: JSON 변환기는 응답용 ObjectMapper와 따로 둔다. 응답 JSON 설정이 바뀌어도 캐시 값의 형식이 바뀌지 않게 하려는 것이다.
 * DTO 필드가 바뀌면 키의 v1을 올려 예전 형식의 값을 읽지 않게 한다. 그래도 읽지 못한 값은 미적중으로 보고 지운다.
 */
@Slf4j
@Component
public class RedisPublicPerformanceDetailCache implements PublicPerformanceDetailCache {

    static final String KEY_PREFIX = "performance:detail:v1:";
    private static final String METRIC = "performance.detail.cache";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final Duration ttl;
    private final long jitterMillis;
    private final RedisCircuitBreaker breaker;
    private final MeterRegistry meters;

    @Autowired
    public RedisPublicPerformanceDetailCache(
            StringRedisTemplate redis,
            MeterRegistry meters,
            @Value("${performance.detail-cache.ttl:10m}") Duration ttl,
            @Value("${performance.detail-cache.jitter:60s}") Duration jitter,
            @Value("${performance.detail-cache.failure-threshold:5}") int failureThreshold,
            @Value("${performance.detail-cache.open-duration:10s}") Duration openDuration) {
        this(redis, meters, ttl, jitter, new RedisCircuitBreaker(failureThreshold, openDuration, System::nanoTime));
    }

    RedisPublicPerformanceDetailCache(
            StringRedisTemplate redis,
            MeterRegistry meters,
            Duration ttl,
            Duration jitter,
            RedisCircuitBreaker breaker) {
        this.redis = redis;
        this.meters = meters;
        this.ttl = ttl;
        this.jitterMillis = jitter.toMillis();
        this.breaker = breaker;
    }

    static String key(Long performanceId) {
        return KEY_PREFIX + performanceId;
    }

    /**
     * 기동 직후 Redis에 미리 연결한다. 실패해도 기동은 계속한다.
     * Note: 첫 연결은 Netty 초기화 때문에 명령 제한 시간(100ms)을 넘길 수 있다. 미리 연결하지 않으면 기동 직후 요청들이
     * 실패로 세어져 Redis를 건너뛰는 상태로 시작할 수 있다. 그래서 여기서는 건너뛰기 판단(RedisCircuitBreaker)에 넣지 않고 몇 번 다시 시도한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                redis.hasKey(key(0L));
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        log.warn("Redis에 미리 연결하지 못했다. 상세 캐시는 요청 때 다시 시도한다. cause={}", last.toString());
    }

    @Override
    public Optional<PublicPerformanceDetail> find(Long performanceId) {
        String key = key(performanceId);
        Attempt<String> value = call("get", () -> redis.opsForValue().get(key));
        if (!value.done()) {
            return Optional.empty();
        }
        if (value.result() == null) {
            count("get", "miss");
            return Optional.empty();
        }
        PublicPerformanceDetail detail;
        try {
            detail = json.readValue(value.result(), PublicPerformanceDetail.class);
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
    private Optional<PublicPerformanceDetail> discard(String key, String reason) {
        log.warn("공연 상세 캐시 값을 쓸 수 없어 지운다. key={}, reason={}", key, reason);
        count("get", "invalid");
        call("evict", () -> redis.delete(key));
        return Optional.empty();
    }

    /** 응답 변환이 null로 읽으면 안 되는 필드만 본다. period·castInfo처럼 null을 허용하는 필드는 보지 않는다. */
    private static boolean isUsable(PublicPerformanceDetail detail, Long performanceId) {
        return detail != null
                && performanceId.equals(detail.id())
                && detail.venue() != null
                && detail.title() != null
                && detail.genre() != null
                && detail.operationStatus() != null
                && detail.createdAt() != null
                && detail.updatedAt() != null
                && detail.gradePrices() != null
                && detail.gradePrices().stream().allMatch(price -> price != null && price.gradeName() != null);
    }

    @Override
    public void put(PublicPerformanceDetail detail) {
        String value;
        try {
            value = json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("공연 상세 캐시 값을 만들지 못했다. performanceId={}", detail.id(), e);
            count("put", "invalid");
            return;
        }
        Duration expiresIn = ttl.plusMillis(ThreadLocalRandom.current().nextLong(jitterMillis + 1));
        Attempt<Boolean> stored = call("put", () -> {
            redis.opsForValue().set(key(detail.id()), value, expiresIn);
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
            log.warn("공연 상세 캐시를 지우지 못했다. 이전 값은 TTL 안에 만료된다. key={}", key);
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
        try {
            T result = command.get();
            breaker.recordSuccess();
            return new Attempt<>(true, result);
        } catch (RuntimeException e) {
            breaker.recordFailure(e);
            log.debug("Redis 명령 실패. op={}", op, e);
            count(op, "error");
            return Attempt.notDone();
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
