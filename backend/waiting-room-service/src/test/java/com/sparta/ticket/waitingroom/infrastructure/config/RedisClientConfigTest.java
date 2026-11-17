package com.sparta.ticket.waitingroom.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.waitingroom.support.WaitingRoomIntegrationTest;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisClientConfigTest extends WaitingRoomIntegrationTest {

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Test
    @DisplayName("Lettuce 클라이언트는 명령별 타임아웃 타이머를 쓰지 않는다")
    void commandTimeoutTimerIsOff() {
        AbstractRedisClient client = connectionFactory.getNativeClient();

        assertThat(client).isNotNull();
        ClientOptions options = client.getOptions();
        assertThat(options.getTimeoutOptions().isTimeoutCommands()).isFalse();
    }

    @Test
    @DisplayName("Spring Data Redis가 응답을 기다리는 시간 제한은 그대로 걸린다")
    void springWaitTimeoutStays() {
        // 0이면 Lettuce Futures.awaitOrCancel이 제한 없이 기다린다
        assertThat(connectionFactory.getTimeout()).isPositive();
    }
}
