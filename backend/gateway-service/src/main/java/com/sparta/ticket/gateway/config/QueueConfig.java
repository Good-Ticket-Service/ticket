package com.sparta.ticket.gateway.config;

import com.sparta.ticket.gateway.infrastructure.client.LoadBalancedWebClients;
import com.sparta.ticket.gateway.infrastructure.client.WaitingRoomClient;
import com.sparta.ticket.gateway.queue.ProtectedScopes;
import com.sparta.ticket.gateway.queue.QueueProperties;
import com.sparta.ticket.gateway.queue.QueueTokenVerifier;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** 대기열 구성: 입장 토큰 검증기, 보호 목록 클라이언트와 주기 갱신. 토큰 키는 기동 때 검사해 설정 누락을 드러낸다. */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueConfig {

    @Bean
    public QueueTokenVerifier queueTokenVerifier(QueueProperties properties) {
        return new QueueTokenVerifier(properties.tokenSecret());
    }

    @Bean
    public WaitingRoomClient waitingRoomClient(
            WebClient.Builder webClientBuilder,
            ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            QueueProperties properties) {
        WebClient webClient = LoadBalancedWebClients.create(
                webClientBuilder, loadBalancer, "reservation-service", properties.protectedScopesTimeout());
        return new WaitingRoomClient(webClient, properties.protectedScopesTimeout());
    }

    /** SmartLifecycle이라 컨텍스트가 뜨면 목록 받기를 시작하고 내려갈 때 멈춘다. closeAt 비교는 서버 시계다(NTP 전제). */
    @Bean
    public ProtectedScopes protectedScopes(WaitingRoomClient client, QueueProperties properties) {
        return new ProtectedScopes(
                client::fetchProtectedRooms, properties.protectedScopesInterval(), Clock.systemUTC());
    }
}
