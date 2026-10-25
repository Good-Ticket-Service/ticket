package com.sparta.ticket.reservation.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 만료·결과 확인의 시간 판정은 이 Clock을 쓴다. 테스트는 고정할 수 있는 Clock으로 바꾼다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
