package com.sparta.ticket.gateway.security;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, JwtProperties.class})
public class AuthConfig {

    /**
     * Note: Boot가 만든 WebClient.Builder를 복제(clone)해서 쓴다. @LoadBalanced WebClient.Builder 빈을 새로 정의하면 Boot 기본 빌더가
     * 사라지고, 그 빌더에 baseUrl을 지정하면 같은 빈을 주입받는 다른 코드까지 user-service 주소를 갖게 된다.
     * 타임아웃은 Gateway 라우트 설정(response-timeout)과 별개라서 이 클라이언트에 직접 건다.
     */
    @Bean
    public UserLookup userLookup(
            WebClient.Builder webClientBuilder,
            ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            AuthProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int)
                        properties.userLookupTimeout().toMillis())
                .responseTimeout(properties.userLookupTimeout());
        WebClient webClient = webClientBuilder
                .clone()
                .baseUrl("http://user-service")
                .filter(loadBalancer)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        UserServiceClient client = new UserServiceClient(webClient, properties.userLookupTimeout());
        return new CachedUserLookup(client::fetch, properties.userCacheTtl());
    }
}
