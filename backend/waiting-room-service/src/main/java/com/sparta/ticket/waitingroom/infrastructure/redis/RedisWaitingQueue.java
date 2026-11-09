package com.sparta.ticket.waitingroom.infrastructure.redis;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.waitingroom.domain.AdmissionPolicy;
import com.sparta.ticket.waitingroom.domain.AdmissionResult;
import com.sparta.ticket.waitingroom.domain.QueueAdmission;
import com.sparta.ticket.waitingroom.domain.QueuePosition;
import com.sparta.ticket.waitingroom.domain.QueueState;
import com.sparta.ticket.waitingroom.domain.WaitingQueue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis로 구현한 room별 대기 줄.
 *
 * Note: 순서는 서버 시계가 아니라 Redis INCR 번호로 정한다. Redis는 명령을 하나씩 실행하므로 같은 밀리초에 몰리거나
 * 서버가 여러 대여도 번호가 겹치지 않는다. 진입을 Lua 하나로 묶는 이유는 순서 보장이 아니라(Sorted Set이 score로 정렬한다)
 * 같은 회원의 거의 동시 요청에서 늦게 받은 번호가 남아 먼저 온 회원이 밀리는 일을 막고, 왕복을 한 번으로 줄이기 위해서다.
 * 중간에 끊겨도 빈 번호만 남고 위치는 ZRANK로 세므로 무해하다.
 */
@Component
public class RedisWaitingQueue implements WaitingQueue {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ENTER_SCRIPT = loadScript("queue/enter.lua", List.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> POSITION_SCRIPT = loadScript("queue/position.lua", List.class);

    private static final RedisScript<Long> LEAVE_SCRIPT = loadScript("queue/leave.lua", Long.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT_SCRIPT = loadScript("queue/admit.lua", List.class);

    /** enter.lua·position.lua가 돌려주는 { 종류, 값 }의 종류. 줄에 서 있으면 값이 앞 인원, 입장해 있으면 입장 만료 시각(ms)이다. */
    private static final long WAITING = 0;

    private static final long ENTERED = 1;

    private final StringRedisTemplate redis;

    public RedisWaitingQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 입장 확인과 줄 서기를 Lua 하나로 묶는다. 따로 하면 그 사이 입장한 회원이 줄 끝에 다시 서서 입장을 두 번 받는다. */
    @Override
    public QueueState enter(long roomId, long userId) {
        List<String> keys = List.of(
                QueueKeys.waiting(roomId),
                QueueKeys.seq(roomId),
                QueueKeys.seen(roomId),
                QueueKeys.active(roomId),
                QueueKeys.roomConfig(roomId));
        List<?> result = execute(ENTER_SCRIPT, keys, String.valueOf(userId));
        return toState(result).orElseThrow();
    }

    /** 위치 확인과 조회 시각 기록을 Lua 하나로 묶는다. 따로 하면 그 사이 줄에서 빠진 회원의 조회 시각이 남는다. */
    @Override
    public Optional<QueueState> find(long roomId, long userId) {
        List<String> keys = List.of(QueueKeys.waiting(roomId), QueueKeys.seen(roomId), QueueKeys.active(roomId));
        List<?> result = execute(POSITION_SCRIPT, keys, String.valueOf(userId));
        return toState(result);
    }

    /** Lua가 돌려준 { 종류, 값 }을 대기열 상태로 바꾼다. 줄에도 입장 목록에도 없으면 비어 있다. */
    private static Optional<QueueState> toState(List<?> result) {
        long kind = number(result, 0);
        long value = number(result, 1);
        if (kind == WAITING) {
            return Optional.of(new QueuePosition(value));
        }
        if (kind == ENTERED) {
            return Optional.of(new QueueAdmission(Instant.ofEpochMilli(value)));
        }
        return Optional.empty();
    }

    /**
     * 대기 줄과 조회 시각을 Lua 하나로 함께 지운다. 따로 지우면 그 사이 순번 조회가 끼어들 때 줄에는 없는데 조회 시각만 남거나,
     * 조회 시각이 지워진 회원이 줄에 남아 이탈자로 잘못 정리될 수 있다.
     */
    @Override
    public void leave(long roomId, long userId) {
        List<String> keys = List.of(QueueKeys.waiting(roomId), QueueKeys.seen(roomId));
        execute(LEAVE_SCRIPT, keys, String.valueOf(userId));
    }

    /** 정리·인원 계산·꺼내기·입장 등록을 Lua 하나로 처리한다. 순서와 이유는 admit.lua 주석에 있다. */
    @Override
    public AdmissionResult admit(long roomId, AdmissionPolicy policy) {
        List<String> keys = List.of(
                QueueKeys.waiting(roomId),
                QueueKeys.seen(roomId),
                QueueKeys.active(roomId),
                QueueKeys.lastAdmit(roomId),
                QueueKeys.roomConfig(roomId));
        List<?> result = execute(
                ADMIT_SCRIPT,
                keys,
                String.valueOf(policy.admitPerSecond()),
                String.valueOf(policy.burst().toMillis()),
                String.valueOf(policy.maxActive()),
                String.valueOf(policy.entryTtl().toMillis()),
                String.valueOf(policy.leaveAfter().toMillis()),
                String.valueOf(policy.leaveBatchSize()));
        return new AdmissionResult(
                number(result, 0), number(result, 1), number(result, 2), number(result, 3), number(result, 4));
    }

    private static long number(List<?> values, int index) {
        return ((Number) values.get(index)).longValue();
    }

    /** Redis에 연결하지 못하면 대기열 없이 통과시키지 않고 503으로 거절한다(fail-closed). 통과시키면 막으려던 요청이 예매 서버로 몰린다. */
    private <T> T execute(RedisScript<T> script, List<String> keys, String... args) {
        try {
            return redis.execute(script, keys, (Object[]) args);
        } catch (DataAccessException e) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static <T> RedisScript<T> loadScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(resultType);
        return script;
    }
}
