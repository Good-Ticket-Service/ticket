package com.sparta.ticket.gateway.config;

import com.sparta.ticket.gateway.auth.AuthProperties;
import com.sparta.ticket.gateway.auth.CachedUserLookup;
import com.sparta.ticket.gateway.auth.JwtProperties;
import com.sparta.ticket.gateway.auth.UserLookup;
import com.sparta.ticket.gateway.infrastructure.client.LoadBalancedWebClients;
import com.sparta.ticket.gateway.infrastructure.client.UserServiceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, JwtProperties.class})
public class AuthConfig {

    @Bean
    public UserServiceClient userServiceClient(
            WebClient.Builder webClientBuilder,
            ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            AuthProperties properties) {
        WebClient webClient = LoadBalancedWebClients.create(
                webClientBuilder, loadBalancer, "user-service", properties.userLookupTimeout());
        return new UserServiceClient(webClient, properties.userLookupTimeout());
    }

    @Bean
    public UserLookup userLookup(UserServiceClient client, AuthProperties properties) {
        return new CachedUserLookup(client::fetch, properties.userCacheTtl(), properties.userCacheMaxSize());
    }
}
