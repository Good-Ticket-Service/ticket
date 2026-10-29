package com.sparta.ticket.fakepg.infrastructure.config;

import com.sparta.ticket.fakepg.application.service.fault.FaultRandom;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class FakePgConfig {

    /** 토스 응답의 시각은 한국 시간(+09:00)이다. */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    /** 장애 확률 판단용. fake-pg.fault-seed를 정하면 같은 순서의 판단이 같은 결과를 낸다. 비우면 실행마다 다르다. */
    @Bean
    public FaultRandom faultRandom(@Value("${fake-pg.fault-seed:#{null}}") Long seed) {
        return new FaultRandom(seed);
    }

    /** 지연 승인(늦은 승인)을 나중에 기록하는 스케줄러. */
    @Bean
    public TaskScheduler delayedApprovalScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("delayed-approval-");
        scheduler.initialize();
        return scheduler;
    }
}
