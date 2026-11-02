package com.sparta.ticket.gateway.queue;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 입장 토큰 검사 구성. 대기열이 꺼져 있어도 키는 검사해 기동 때 설정 누락을 드러낸다. */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueTokenConfig {

    @Bean
    public QueueTokenVerifier queueTokenVerifier(QueueProperties properties) {
        return new QueueTokenVerifier(properties.tokenSecret());
    }
}
