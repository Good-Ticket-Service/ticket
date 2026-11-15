package com.sparta.ticket.reservation.seat.infrastructure.redis;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis로 구현한 선점 저장소(#449). 키 구성은 {@link SeatHoldKeys}, 판단은 resources/seat/*.lua에 있다.
 * Note: 확인과 변경을 Lua 하나로 묶는다. Redis는 스크립트를 하나씩 끝까지 실행하므로, 같은 좌석·같은 회원의 동시 요청이
 * 확인과 기록 사이에 섞이지 않는다.
 */
@Component
public class RedisSeatHoldStore implements SeatHoldStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> HOLD_SCRIPT = loadScript("seat/hold.lua", List.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RELEASE_SCRIPT = loadScript("seat/release.lua", List.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CONVERT_BEGIN_SCRIPT = loadScript("seat/convert-begin.lua", List.class);

    private static final RedisScript<Long> CONVERT_FINISH_SCRIPT = loadScript("seat/convert-finish.lua", Long.class);

    private static final RedisScript<Long> CONVERT_ABORT_SCRIPT = loadScript("seat/convert-abort.lua", Long.class);

    private static final RedisScript<Long> CLEAR_CONVERTED_USER_SCRIPT =
            loadScript("seat/clear-converted-user.lua", Long.class);

    /** hold.lua 반환 코드 순서와 같다. */
    private static final HoldOutcome[] HOLD_OUTCOMES = {
        HoldOutcome.CREATED,
        HoldOutcome.EXISTING,
        HoldOutcome.CONVERTED_USER,
        HoldOutcome.TAKEN,
        HoldOutcome.LIMIT,
        HoldOutcome.MINE_IN_DB
    };

    /** release.lua 반환 코드 순서와 같다. */
    private static final ReleaseOutcome[] RELEASE_OUTCOMES = {
        ReleaseOutcome.GONE,
        ReleaseOutcome.FORBIDDEN,
        ReleaseOutcome.BUSY,
        ReleaseOutcome.CONVERTED,
        ReleaseOutcome.RELEASED
    };

    /** convert-begin.lua 반환 코드 순서와 같다. */
    private static final ConvertOutcome[] CONVERT_OUTCOMES = {
        ConvertOutcome.NOT_FOUND,
        ConvertOutcome.FORBIDDEN,
        ConvertOutcome.CONVERTED,
        ConvertOutcome.EXPIRED,
        ConvertOutcome.STARTED,
        ConvertOutcome.CONVERTING_EXPIRING
    };

    private static final String CONVERTED_PREFIX = "CONVERTED:";

    private final StringRedisTemplate redis;

    public RedisSeatHoldStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public HoldAttempt hold(StoredHold hold, Long heldInDbBy, Instant now) {
        List<String> args = new ArrayList<>();
        args.add(hold.holdId());
        args.add(String.valueOf(hold.scheduleSeatId()));
        args.add(String.valueOf(hold.expiresAt().toEpochMilli()));
        args.add(String.valueOf(ttlMillis(hold.expiresAt(), now)));
        args.add(String.valueOf(now.toEpochMilli()));
        args.add(heldInDbBy == null ? "" : String.valueOf(heldInDbBy));
        toHash(hold).forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        List<String> result = executeList(HOLD_SCRIPT, keys(hold), args.toArray(String[]::new));
        HoldOutcome outcome = HOLD_OUTCOMES[code(result)];
        return switch (outcome) {
            case CREATED, EXISTING -> new HoldAttempt(outcome, result.get(1), null);
            case CONVERTED_USER, MINE_IN_DB -> new HoldAttempt(outcome, null, Long.valueOf(result.get(1)));
            default -> new HoldAttempt(outcome, null, null);
        };
    }

    @Override
    public Optional<StoredHold> find(Long scheduleId, String holdId) {
        Map<Object, Object> hash = call(() -> redis.opsForHash().entries(SeatHoldKeys.hold(scheduleId, holdId)));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromHash(holdId, hash));
    }

    @Override
    public ReleaseAttempt release(Long scheduleId, String holdId, Long userId) {
        Optional<StoredHold> hold = find(scheduleId, holdId);
        if (hold.isEmpty()) {
            return new ReleaseAttempt(ReleaseOutcome.GONE, null);
        }
        // 좌석 ID는 선점 키에만 있어 먼저 읽는다. 읽은 뒤 사라지거나 바뀌어도 Lua가 선점 키를 다시 보고 판단한다
        List<String> result = executeList(
                RELEASE_SCRIPT,
                SeatHoldKeys.all(scheduleId, hold.get().scheduleSeatId(), userId, holdId, gradeId(hold.get())),
                holdId,
                String.valueOf(userId));
        ReleaseOutcome outcome = RELEASE_OUTCOMES[code(result)];
        Long reservationId = outcome == ReleaseOutcome.CONVERTED ? Long.valueOf(result.get(1)) : null;
        return new ReleaseAttempt(outcome, reservationId);
    }

    @Override
    public ConvertStart beginConvert(Long scheduleId, String holdId, Long userId, Instant now, Duration minRemain) {
        List<String> result = executeList(
                CONVERT_BEGIN_SCRIPT,
                List.of(SeatHoldKeys.hold(scheduleId, holdId)),
                String.valueOf(userId),
                String.valueOf(now.plus(minRemain).toEpochMilli()));
        ConvertOutcome outcome = CONVERT_OUTCOMES[code(result)];
        Long reservationId = outcome == ConvertOutcome.CONVERTED ? Long.valueOf(result.get(1)) : null;
        return new ConvertStart(outcome, reservationId);
    }

    @Override
    public void finishConvert(StoredHold hold, Long reservationId, Instant userKeyExpiresAt, Instant now) {
        call(() -> redis.execute(
                CONVERT_FINISH_SCRIPT,
                keys(hold),
                hold.holdId(),
                String.valueOf(reservationId),
                String.valueOf(ttlMillis(userKeyExpiresAt, now)),
                String.valueOf(hold.scheduleSeatId())));
    }

    @Override
    public void abortConvert(StoredHold hold, boolean drop) {
        call(() -> redis.execute(
                CONVERT_ABORT_SCRIPT,
                keys(hold),
                hold.holdId(),
                drop ? "drop" : "revert",
                String.valueOf(hold.scheduleSeatId())));
    }

    @Override
    public boolean clearConvertedUser(Long scheduleId, Long userId, Long reservationId) {
        Long result = call(() -> redis.execute(
                CLEAR_CONVERTED_USER_SCRIPT,
                List.of(SeatHoldKeys.user(scheduleId, userId)),
                CONVERTED_PREFIX + reservationId));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public Set<Long> heldSeatIds(Long scheduleId, Instant now) {
        Set<String> members = call(() -> redis.opsForZSet()
                .rangeByScore(SeatHoldKeys.held(scheduleId), now.toEpochMilli() + 1, Double.POSITIVE_INFINITY));
        Set<Long> ids = new HashSet<>();
        if (members != null) {
            members.forEach(member -> ids.add(Long.valueOf(member)));
        }
        return ids;
    }

    @Override
    public Map<Long, Long> heldCountByGrade(Long scheduleId, Collection<Long> venueSeatGradeIds, Instant now) {
        List<Long> grades = List.copyOf(venueSeatGradeIds);
        if (grades.isEmpty()) {
            return Map.of();
        }
        // 등급마다 개수만 센다(ZCOUNT). 한 번의 왕복으로 보낸다
        List<Object> counts = call(() -> redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long grade : grades) {
                connection
                        .zSetCommands()
                        .zCount(
                                SeatHoldKeys.heldGrade(scheduleId, grade).getBytes(StandardCharsets.UTF_8),
                                Range.closed(now.toEpochMilli() + 1, Double.POSITIVE_INFINITY));
            }
            return null;
        }));
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < grades.size(); i++) {
            result.put(grades.get(i), ((Number) counts.get(i)).longValue());
        }
        return result;
    }

    private static List<String> keys(StoredHold hold) {
        return SeatHoldKeys.all(hold.scheduleId(), hold.scheduleSeatId(), hold.userId(), hold.holdId(), gradeId(hold));
    }

    private static Long gradeId(StoredHold hold) {
        return hold.validation().seats().get(0).venueSeatGradeId();
    }

    /** 키 만료로 넘길 상대 시간(ms). 이미 지난 시각이면 1ms로 둔다(Redis는 0 이하 만료를 받지 않는다). */
    private static long ttlMillis(Instant expiresAt, Instant now) {
        return Math.max(1L, Duration.between(now, expiresAt).toMillis());
    }

    /** 선점 키(HASH)의 필드. null인 값은 넣지 않는다(HASH는 null을 담지 못한다). */
    private static Map<String, String> toHash(StoredHold hold) {
        SaleValidation validation = hold.validation();
        SaleValidationSeat seat = validation.seats().get(0);
        Map<String, String> hash = new LinkedHashMap<>();
        put(hash, "scheduleId", hold.scheduleId());
        put(hash, "userId", hold.userId());
        put(hash, "scheduleSeatId", hold.scheduleSeatId());
        put(hash, "expiresAt", hold.expiresAt().toEpochMilli());
        put(hash, "state", hold.state());
        put(hash, "reservationId", hold.reservationId());
        put(hash, "performanceId", validation.performanceId());
        put(hash, "sellerId", validation.sellerId());
        put(hash, "configVersion", validation.configVersion());
        put(hash, "policyVersion", validation.policyVersion());
        put(hash, "scheduleStartAt", validation.scheduleStartAt());
        put(hash, "performanceTitle", validation.performanceTitle());
        put(hash, "genre", validation.genre());
        put(hash, "venueSeatId", seat.venueSeatId());
        put(hash, "venueSeatGradeId", seat.venueSeatGradeId());
        put(hash, "gradeName", seat.gradeName());
        put(hash, "basePrice", seat.basePrice());
        return hash;
    }

    private static void put(Map<String, String> hash, String field, Object value) {
        if (value != null) {
            hash.put(field, value.toString());
        }
    }

    private static StoredHold fromHash(String holdId, Map<Object, Object> hash) {
        Long scheduleId = longOf(hash, "scheduleId");
        SaleValidationSeat seat = new SaleValidationSeat(
                longOf(hash, "venueSeatId"),
                longOf(hash, "venueSeatGradeId"),
                text(hash, "gradeName"),
                Integer.parseInt(text(hash, "basePrice")));
        String startAt = text(hash, "scheduleStartAt");
        SaleValidation validation = new SaleValidation(
                true,
                null,
                scheduleId,
                longOf(hash, "performanceId"),
                longOf(hash, "sellerId"),
                Long.parseLong(text(hash, "configVersion")),
                Long.parseLong(text(hash, "policyVersion")),
                startAt == null ? null : LocalDateTime.parse(startAt),
                text(hash, "performanceTitle"),
                text(hash, "genre"),
                List.of(seat));
        return new StoredHold(
                holdId,
                scheduleId,
                longOf(hash, "userId"),
                longOf(hash, "scheduleSeatId"),
                validation,
                Instant.ofEpochMilli(Long.parseLong(text(hash, "expiresAt"))),
                HoldState.valueOf(text(hash, "state")),
                longOf(hash, "reservationId"));
    }

    private static String text(Map<Object, Object> hash, String field) {
        Object value = hash.get(field);
        return value == null ? null : value.toString();
    }

    private static Long longOf(Map<Object, Object> hash, String field) {
        String value = text(hash, field);
        return value == null ? null : Long.valueOf(value);
    }

    private static int code(List<String> result) {
        return Integer.parseInt(result.get(0));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<String> executeList(RedisScript<List> script, List<String> keys, String... args) {
        List<?> result = call(() -> redis.execute(script, keys, (Object[]) args));
        List<String> values = new ArrayList<>();
        for (Object value : result) {
            values.add(value == null ? "" : value.toString());
        }
        return values;
    }

    /** Redis에 연결하지 못하면 503으로 거절한다(쿠폰 발급과 같은 오류). DB 방식으로 대신하지 않는다. */
    private static <T> T call(RedisCall<T> action) {
        try {
            return action.run();
        } catch (DataAccessException e) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @FunctionalInterface
    private interface RedisCall<T> {
        T run();
    }

    private static <T> RedisScript<T> loadScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(resultType);
        return script;
    }
}
