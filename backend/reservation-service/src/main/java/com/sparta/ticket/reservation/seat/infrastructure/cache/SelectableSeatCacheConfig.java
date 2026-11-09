package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.sparta.ticket.reservation.seat.application.SelectableSeatProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 좌석 배치도 스냅샷 캐시 설정. refresh 전용 스레드를 빈으로 두어 테스트가 호출한 스레드에서 바로 실행하는 것으로 바꾸게 한다(#311).
 * Note: 만료 시각을 재는 Ticker는 RemainingSeatCacheConfig의 빈 하나를 함께 쓴다. Ticker 빈이 둘이면 타입으로 주입받는 곳이 모호해진다.
 */
@Configuration
@EnableConfigurationProperties(SelectableSeatProperties.class)
public class SelectableSeatCacheConfig {

    @Bean(destroyMethod = "shutdown")
    public SeatSnapshotRefreshExecutor seatSnapshotRefreshExecutor() {
        return SeatSnapshotRefreshExecutor.dedicatedThreads();
    }
}
