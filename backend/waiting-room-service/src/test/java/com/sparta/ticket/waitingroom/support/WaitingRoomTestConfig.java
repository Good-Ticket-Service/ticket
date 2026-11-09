package com.sparta.ticket.waitingroom.support;

import java.time.LocalDateTime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트용 시계. 시각은 테스트가 정한다. */
@TestConfiguration
public class WaitingRoomTestConfig {

    /** 테스트 시계가 시작하는 시각. 통합 테스트는 매번 이 시각으로 되돌리고 시작한다. */
    public static final LocalDateTime START_TIME = LocalDateTime.of(2026, 11, 1, 20, 0);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(START_TIME);
    }
}
