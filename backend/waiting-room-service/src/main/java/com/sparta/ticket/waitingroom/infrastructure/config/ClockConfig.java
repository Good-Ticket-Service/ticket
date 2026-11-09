package com.sparta.ticket.waitingroom.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 대기열의 시각 판정(room 오픈·마감, 입장 만료)이 함께 쓰는 Clock. 테스트는 고정할 수 있는 Clock으로 바꾼다. */
@Configuration
public class ClockConfig {

    /** 컨테이너 TZ와 상관없이 같은 기준으로 계산하려고 존을 고정한다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
