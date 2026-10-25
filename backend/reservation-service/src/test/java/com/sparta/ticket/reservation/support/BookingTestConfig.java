package com.sparta.ticket.reservation.support;

import java.time.LocalDateTime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트용 대역. 시각을 테스트가 정한다. */
@TestConfiguration
public class BookingTestConfig {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(LocalDateTime.of(2026, 11, 1, 20, 0));
    }
}
