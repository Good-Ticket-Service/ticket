package com.sparta.ticket.waitingroom.infrastructure.scheduler;

import com.sparta.ticket.waitingroom.application.QueueAdmissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 입장 스케줄러를 켠다. waiting-room.scheduler.enabled=true일 때만 켜진다.
 * Note: 쿠폰 스케줄러처럼 설정이 없으면 꺼진다. 통합 테스트는 이 설정을 넣지 않으므로 꺼진 채로 돌고, 입장 처리를 직접 불러 확인한다.
 * 운영 값은 config-repo에서 켠다.
 * Note: @EnableScheduling을 쓰지 않는다. booking·쿠폰과 같은 이유로 대기열 전용 스레드에서 돌리고 TaskScheduler 빈으로 내놓지 않는다.
 */
@Configuration
@EnableConfigurationProperties(QueueSchedulerProperties.class)
@ConditionalOnProperty(prefix = "waiting-room.scheduler", name = "enabled", havingValue = "true")
public class QueueSchedulingConfig {

    @Bean
    public QueueAdmissionScheduleRunner queueAdmissionScheduleRunner(
            QueueSchedulerProperties properties, QueueAdmissionService admissionService) {
        return new QueueAdmissionScheduleRunner(properties, admissionService);
    }
}
