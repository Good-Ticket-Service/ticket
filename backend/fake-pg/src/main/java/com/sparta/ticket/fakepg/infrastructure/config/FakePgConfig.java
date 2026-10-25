package com.sparta.ticket.fakepg.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Random;
import java.util.random.RandomGenerator;
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

    /** 장애 확률 판단용. 여러 요청 스레드가 함께 쓰므로 스레드 안전한 Random을 쓴다. */
    @Bean
    public RandomGenerator faultRandom() {
        return new Random();
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
