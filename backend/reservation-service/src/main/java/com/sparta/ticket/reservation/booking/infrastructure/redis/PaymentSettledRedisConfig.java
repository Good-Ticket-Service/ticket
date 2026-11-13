package com.sparta.ticket.reservation.booking.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 다른 인스턴스가 보낸 결제 결과 알림을 받는다. Redis가 멈추면 구독이 끊기고 컨테이너가 다시 붙는다.
 * 그동안 이 인스턴스의 기다리는 조회는 대기 시간 끝에 결제 중으로 답하고, 클라이언트의 다음 조회가 등록 직후 읽어 결과를 본다.
 */
@Configuration
class PaymentSettledRedisConfig {

    @Bean
    RedisMessageListenerContainer paymentSettledListenerContainer(
            RedisConnectionFactory connectionFactory, PaymentSettledBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new ChannelTopic(PaymentSettledBroadcaster.CHANNEL));
        // 받은 알림마다 새 스레드를 만들지 않게 구독 스레드에서 바로 처리한다. 처리는 대기 목록에서 꺼내 답하는 일뿐이라 짧다
        container.setTaskExecutor(new SyncTaskExecutor());
        return container;
    }
}
