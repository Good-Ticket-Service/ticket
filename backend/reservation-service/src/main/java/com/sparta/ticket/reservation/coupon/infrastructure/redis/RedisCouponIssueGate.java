package com.sparta.ticket.reservation.coupon.infrastructure.redis;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis로 구현한 쿠폰 발급 판단. 키는 쿠폰마다 정보 키(Hash: total, deleted)와 회원 키(Set: 받은 회원 ID) 두 개다(CouponKeys).
 * 발급 수량은 따로 두지 않고 회원 수(SCARD)로 센다. 두 값이 어긋날 일이 없고, 되돌리기도 회원 하나만 빼면 된다.
 * Note: 판단과 변경을 Lua 하나로 묶는다. Redis는 스크립트를 하나씩 끝까지 실행하므로, 남은 한 자리를 두 요청이 함께 통과하거나
 * 삭제·총 수량 변경이 비교와 변경 사이에 발급과 섞이는 일이 없다.
 */
@Component
public class RedisCouponIssueGate implements CouponIssueGate {

    private static final RedisScript<Long> ISSUE_SCRIPT = loadScript("coupon/issue.lua");

    private static final RedisScript<Long> LOAD_SCRIPT = loadScript("coupon/load.lua");

    private static final RedisScript<Long> RESET_SCRIPT = loadScript("coupon/reset.lua");

    private static final RedisScript<Long> CHANGE_TOTAL_SCRIPT = loadScript("coupon/change-total.lua");

    private static final RedisScript<Long> RESTORE_TOTAL_SCRIPT = loadScript("coupon/restore-total.lua");

    private static final RedisScript<Long> MARK_DELETED_SCRIPT = loadScript("coupon/mark-deleted.lua");

    private static final RedisScript<Long> UNMARK_DELETED_SCRIPT = loadScript("coupon/unmark-deleted.lua");

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SNAPSHOT_SCRIPT = loadScript("coupon/snapshot.lua", List.class);

    private static final RedisScript<Long> ADD_ISSUED_SCRIPT = loadScript("coupon/add-issued.lua");

    private static final RedisScript<Long> SYNC_META_SCRIPT = loadScript("coupon/sync-meta.lua");

    private static final RedisScript<Long> EXPIRE_SCRIPT = loadScript("coupon/expire.lua");

    /** 회원 키를 나눠 읽을 때 한 번에 가져오는 개수(SSCAN COUNT). 큰 키를 한 번에 읽어 다른 명령을 오래 막지 않는다. */
    private static final int SCAN_COUNT = 1_000;

    /** issue.lua 반환값 순서와 같다. */
    private static final IssueDecision[] ISSUE_DECISIONS = {
        IssueDecision.ISSUED,
        IssueDecision.NOT_LOADED,
        IssueDecision.ALREADY_ISSUED,
        IssueDecision.DELETED,
        IssueDecision.PERIOD_CLOSED,
        IssueDecision.SOLD_OUT
    };

    /** change-total.lua 반환값 순서와 같다. */
    private static final TotalChange[] TOTAL_CHANGES = {
        TotalChange.CHANGED, TotalChange.NOT_LOADED, TotalChange.BELOW_ISSUED
    };

    /** mark-deleted.lua 반환값 순서와 같다. */
    private static final DeleteMark[] DELETE_MARKS = {DeleteMark.MARKED, DeleteMark.NOT_LOADED, DeleteMark.ISSUED_EXISTS
    };

    private final StringRedisTemplate redis;

    public RedisCouponIssueGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public IssueDecision tryIssue(Long couponId, Long userId, boolean periodOpen) {
        Long result = execute(ISSUE_SCRIPT, keys(couponId), String.valueOf(userId), periodOpen ? "1" : "0");
        return ISSUE_DECISIONS[result.intValue()];
    }

    @Override
    public boolean isLoaded(Long couponId) {
        return call(() -> Boolean.TRUE.equals(redis.hasKey(CouponKeys.meta(couponId))));
    }

    @Override
    public void loadIfAbsent(
            Long couponId, int totalQuantity, boolean deleted, Collection<Long> issuedUserIds, Duration ttl) {
        // 인자: 총 수량, 삭제 표시, 남겨 둘 시간(초), 받은 회원 ID들
        List<String> args = new ArrayList<>(issuedUserIds.size() + 3);
        args.add(String.valueOf(totalQuantity));
        args.add(deleted ? "1" : "0");
        args.add(String.valueOf(ttl.toSeconds()));
        issuedUserIds.forEach(id -> args.add(String.valueOf(id)));
        execute(LOAD_SCRIPT, keys(couponId), args.toArray(String[]::new));
    }

    @Override
    public void reset(Long couponId, int totalQuantity, Duration ttl) {
        execute(RESET_SCRIPT, keys(couponId), String.valueOf(totalQuantity), String.valueOf(ttl.toSeconds()));
    }

    @Override
    public void cancel(Long couponId, Long userId) {
        call(() -> redis.opsForSet().remove(CouponKeys.users(couponId), String.valueOf(userId)));
    }

    @Override
    public TotalChange changeTotal(Long couponId, int totalQuantity) {
        Long result = execute(CHANGE_TOTAL_SCRIPT, keys(couponId), String.valueOf(totalQuantity));
        return TOTAL_CHANGES[result.intValue()];
    }

    @Override
    public void restoreTotal(Long couponId, int expected, int restoreTo) {
        execute(
                RESTORE_TOTAL_SCRIPT,
                List.of(CouponKeys.meta(couponId)),
                String.valueOf(expected),
                String.valueOf(restoreTo));
    }

    @Override
    public DeleteMark markDeleted(Long couponId) {
        Long result = execute(MARK_DELETED_SCRIPT, keys(couponId));
        return DELETE_MARKS[result.intValue()];
    }

    @Override
    public void unmarkDeleted(Long couponId) {
        execute(UNMARK_DELETED_SCRIPT, List.of(CouponKeys.meta(couponId)));
    }

    /** 쿠폰마다 정보 키 존재 여부와 회원 수를 한 번의 왕복(파이프라인)으로 읽는다. 목록 한 페이지를 쿠폰 수만큼 왕복하지 않는다. */
    @Override
    public Map<Long, Integer> issuedCounts(Collection<Long> couponIds) {
        if (couponIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(couponIds);
        // 쿠폰마다 EXISTS(정보 키), SCARD(회원 키) 두 결과가 차례로 돌아온다
        List<Object> results = call(() -> redis.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection string = (StringRedisConnection) connection;
            for (Long id : ids) {
                string.exists(CouponKeys.meta(id));
                string.sCard(CouponKeys.users(id));
            }
            return null;
        }));
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            // 정보 키가 없으면 회원 키도 믿을 수 없어 넣지 않는다. 호출자가 DB로 센다
            if (Boolean.TRUE.equals(results.get(i * 2))) {
                counts.put(ids.get(i), ((Number) results.get(i * 2 + 1)).intValue());
            }
        }
        return counts;
    }

    @Override
    public Optional<IssueSnapshot> snapshot(Long couponId) {
        // 정보 키가 없으면 빈 배열, 있으면 { 총 수량, 삭제 표시, 받은 회원 수 }
        List<?> result = execute(SNAPSHOT_SCRIPT, keys(couponId));
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IssueSnapshot(
                ((Number) result.get(0)).intValue(),
                ((Number) result.get(1)).intValue() == 1,
                ((Number) result.get(2)).intValue()));
    }

    @Override
    public Set<Long> issuedUserIds(Long couponId) {
        // SMEMBERS는 큰 키를 한 번에 읽는 동안 발급·대기열 명령을 막는다. SSCAN으로 조금씩 나눠 읽는다
        return call(() -> {
            Set<Long> ids = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().count(SCAN_COUNT).build();
            try (Cursor<String> cursor = redis.opsForSet().scan(CouponKeys.users(couponId), options)) {
                cursor.forEachRemaining(id -> ids.add(Long.valueOf(id)));
            }
            return ids;
        });
    }

    @Override
    public void addIssued(Long couponId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        execute(
                ADD_ISSUED_SCRIPT,
                keys(couponId),
                userIds.stream().map(String::valueOf).toArray(String[]::new));
    }

    @Override
    public void syncMeta(Long couponId, int totalQuantity, boolean deleted) {
        execute(
                SYNC_META_SCRIPT,
                List.of(CouponKeys.meta(couponId)),
                String.valueOf(totalQuantity),
                deleted ? "1" : "0");
    }

    @Override
    public void expire(Long couponId, Duration ttl) {
        execute(EXPIRE_SCRIPT, keys(couponId), String.valueOf(ttl.toSeconds()));
    }

    private static List<String> keys(Long couponId) {
        return List.of(CouponKeys.meta(couponId), CouponKeys.users(couponId));
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, String... args) {
        return call(() -> redis.execute(script, keys, (Object[]) args));
    }

    /**
     * Redis에 연결하지 못하면 503으로 거절한다(대기열과 같은 오류). 발급을 DB로 대신하면 두 곳이 따로 판단해 초과 발급이 생긴다.
     * Note: 응답을 못 받은 경우(시간 초과)도 여기로 온다. 발급이면 Redis에 기록됐을 수도 있지만 DB에 저장하지 않으므로
     * 덜 발급되는 쪽이다. 남은 기록은 맞추는 작업(CouponIssueReconcileService)이 DB 기준으로 지운다.
     */
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

    private static RedisScript<Long> loadScript(String path) {
        return loadScript(path, Long.class);
    }

    private static <T> RedisScript<T> loadScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(resultType);
        return script;
    }
}
