package com.sparta.ticket.gateway.infrastructure.client;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Eureka 서비스 이름으로 다른 서비스를 부르는 WebClient를 만든다. user-service·waiting-room-service 클라이언트가 같이 쓴다.
 *
 * Note: Boot가 만든 WebClient.Builder를 복제(clone)해서 쓴다. @LoadBalanced WebClient.Builder 빈을 새로 정의하면 Boot 기본 빌더가
 * 사라지고, 그 빌더에 baseUrl을 지정하면 같은 빈을 주입받는 다른 코드까지 그 서비스 주소를 갖게 된다.
 * 타임아웃은 Gateway 라우트 설정(response-timeout)과 별개라서 이 클라이언트에 직접 건다.
 */
public final class LoadBalancedWebClients {

    private LoadBalancedWebClients() {}

    /**
     * @param serviceId Eureka에 등록된 서비스 이름(예: user-service)
     * @param timeout 연결과 응답을 각각 기다리는 최대 시간
     */
    public static WebClient create(
            WebClient.Builder builder,
            ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            String serviceId,
            Duration timeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
                .responseTimeout(timeout);
        return builder.clone()
                .baseUrl("http://" + serviceId)
                .filter(loadBalancer)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
