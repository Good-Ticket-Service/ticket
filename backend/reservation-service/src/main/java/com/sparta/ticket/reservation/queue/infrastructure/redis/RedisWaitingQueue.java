package com.sparta.ticket.reservation.queue.infrastructure.redis;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis로 구현한 공연별 대기 줄.
 *
 * Note: 순서는 서버 시계가 아니라 Redis INCR 번호로 정한다. Redis는 명령을 하나씩 실행하므로 같은 밀리초에 몰리거나
 * 서버가 여러 대여도 번호가 겹치지 않는다. 진입을 Lua 하나로 묶는 이유는 순서 보장이 아니라(Sorted Set이 score로 정렬한다)
 * 같은 회원의 거의 동시 요청에서 늦게 받은 번호가 남아 먼저 온 회원이 밀리는 일을 막고, 왕복을 한 번으로 줄이기 위해서다.
 * 중간에 끊겨도 빈 번호만 남고 위치는 ZRANK로 세므로 무해하다.
 */
@Component
public class RedisWaitingQueue implements WaitingQueue {

    private static final RedisScript<Long> ENTER_SCRIPT = loadScript("queue/enter.lua");

    private final StringRedisTemplate redis;

    public RedisWaitingQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public QueuePosition enter(long performanceId, long userId) {
        List<String> keys =
                List.of(QueueKeys.waiting(performanceId), QueueKeys.seq(performanceId), QueueKeys.PERFORMANCES);
        Long rank = execute(ENTER_SCRIPT, keys, String.valueOf(userId), String.valueOf(performanceId));
        return new QueuePosition(rank);
    }

    /** Redis에 연결하지 못하면 대기열 없이 통과시키지 않고 503으로 거절한다(fail-closed). 통과시키면 막으려던 요청이 예매 서버로 몰린다. */
    private <T> T execute(RedisScript<T> script, List<String> keys, String... args) {
        try {
            return redis.execute(script, keys, (Object[]) args);
        } catch (DataAccessException e) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static RedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
