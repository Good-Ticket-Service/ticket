package com.sparta.ticket.gateway.queue;

import io.netty.channel.ChannelOption;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 입장 토큰 검사 구성. 키는 기동 때 검사해 설정 누락을 드러낸다. */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueTokenConfig {

    @Bean
    public QueueTokenVerifier queueTokenVerifier(QueueProperties properties) {
        return new QueueTokenVerifier(properties.tokenSecret());
    }

    /** AuthConfig의 user-service 클라이언트와 같이 Boot의 WebClient.Builder를 복제해 쓰고, 타임아웃을 이 클라이언트에 직접 건다. */
    @Bean
    public WaitingRoomClient waitingRoomClient(
            WebClient.Builder webClientBuilder,
            ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            QueueProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int)
                        properties.protectedScopesTimeout().toMillis())
                .responseTimeout(properties.protectedScopesTimeout());
        WebClient webClient = webClientBuilder
                .clone()
                .baseUrl("http://reservation-service")
                .filter(loadBalancer)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        return new WaitingRoomClient(webClient, properties.protectedScopesTimeout());
    }

    /** SmartLifecycle이라 컨텍스트가 뜨면 목록 받기를 시작하고 내려갈 때 멈춘다. closeAt 비교는 서버 시계다(NTP 전제). */
    @Bean
    public ProtectedScopes protectedScopes(WaitingRoomClient client, QueueProperties properties) {
        return new ProtectedScopes(
                client::fetchProtectedRooms, properties.protectedScopesInterval(), Clock.systemUTC());
    }
}
