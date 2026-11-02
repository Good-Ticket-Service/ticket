package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 잔여석 캐시 설정. 캐시 만료 시각을 재는 Ticker를 빈으로 두어 테스트가 가짜 시계로 바꿔 기다리지 않고 만료를 확인하게 한다.
 * Note: 캐시 만료는 Caffeine의 Ticker로 재고, 응답의 asOf는 공용 Clock으로 정한다. 둘은 서로 다른 용도라 맞추지 않는다.
 */
@Configuration
@EnableConfigurationProperties(RemainingSeatProperties.class)
public class RemainingSeatCacheConfig {

    @Bean
    public Ticker remainingSeatCacheTicker() {
        return Ticker.systemTicker();
    }
}
