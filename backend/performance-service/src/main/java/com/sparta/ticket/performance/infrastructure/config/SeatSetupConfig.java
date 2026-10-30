package com.sparta.ticket.performance.infrastructure.config;

import com.sparta.ticket.performance.application.dto.SeatSetupRetryPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 좌석 준비 자동 재시도 설정(이슈 #131에서 정함). 기본값은 최대 8회, 간격 10초에서 두 배씩 늘려 상한 5분이다(첫 시도부터 약 15분).
 * 15분은 409 SEAT_SETUP_IN_USE를 기다리기 위한 길이다. 선점(HOLD)은 10분이고 만료 뒤 정리될 때까지 남는다(이슈 #175).
 * Note: 첫 간격(10초)은 한 번 호출의 최대 시간(connect 1초 + read 5초)보다 길어야 한다. 호출 중에 재시도 스케줄러가 같은
 * 회차를 다시 보내지 않게 하는 임대 시간이기 때문이다.
 */
@Configuration
public class SeatSetupConfig {

    @Bean
    public SeatSetupRetryPolicy seatSetupRetryPolicy(
            @Value("${performance.seat-setup.retry.max-attempts:8}") int maxAttempts,
            @Value("${performance.seat-setup.retry.first-delay:10s}") Duration firstDelay,
            @Value("${performance.seat-setup.retry.max-delay:5m}") Duration maxDelay) {
        return new SeatSetupRetryPolicy(maxAttempts, firstDelay, maxDelay);
    }
}
