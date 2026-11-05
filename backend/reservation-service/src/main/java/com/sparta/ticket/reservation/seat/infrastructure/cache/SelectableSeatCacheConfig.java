package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 좌석 배치도 스냅샷 캐시 설정.
 * Note: 만료 시각을 재는 Ticker는 RemainingSeatCacheConfig의 빈 하나를 함께 쓴다. Ticker 빈이 둘이면 타입으로 주입받는 곳이 모호해진다.
 */
@Configuration
@EnableConfigurationProperties(SelectableSeatProperties.class)
public class SelectableSeatCacheConfig {}
