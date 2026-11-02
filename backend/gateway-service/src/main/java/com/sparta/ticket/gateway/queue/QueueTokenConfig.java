package com.sparta.ticket.gateway.queue;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 입장 토큰 검사 구성. 대기열이 꺼져 있어도 키는 검사해 기동 때 설정 누락을 드러낸다. */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueTokenConfig {

    @Bean
    public QueueTokenVerifier queueTokenVerifier(QueueProperties properties) {
        return new QueueTokenVerifier(properties.tokenSecret());
    }

    /** AuthConfig의 user-service 클라이언트와 같이 Boot의 WebClient.Builder를 복제해 쓰고, 타임아웃을 이 클라이언트에 직접 건다. */
    @Bean
    public PerformanceServiceClient performanceServiceClient(
            WebClient.Builder webClientBuilder,
            ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            QueueProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int)
                        properties.scheduleLookupTimeout().toMillis())
                .responseTimeout(properties.scheduleLookupTimeout());
        WebClient webClient = webClientBuilder
                .clone()
                .baseUrl("http://performance-service")
                .filter(loadBalancer)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        return new PerformanceServiceClient(webClient, properties.scheduleLookupTimeout());
    }

    @Bean
    public ScheduleLookup scheduleLookup(PerformanceServiceClient client, QueueProperties properties) {
        return new CachedScheduleLookup(client::fetchPerformanceId, properties.scheduleCacheTtl());
    }
}
