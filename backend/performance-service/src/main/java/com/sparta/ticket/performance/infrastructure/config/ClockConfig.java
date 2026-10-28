package com.sparta.ticket.performance.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 업무 시각의 기준 시계. DB 시각 컬럼은 KST로 저장하므로 서버 시간대와 무관하게 Asia/Seoul로 고정한다.
 * Note: 현재 시각을 Clock으로 받으면 테스트에서 고정 시각으로 바꿔 판매 기간 같은 시간 조건을 검증할 수 있다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
