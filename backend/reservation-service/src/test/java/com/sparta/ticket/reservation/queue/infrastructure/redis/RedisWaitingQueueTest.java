package com.sparta.ticket.reservation.queue.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisWaitingQueueTest {

    @Test
    @DisplayName("Redis에 연결하지 못하면 대기열 없이 통과시키지 않고 503 SERVICE_UNAVAILABLE로 거절한다")
    void failClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willThrow(new RedisConnectionFailureException("연결 거부"));
        RedisWaitingQueue waitingQueue = new RedisWaitingQueue(redis);

        assertThatThrownBy(() -> waitingQueue.enter(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
