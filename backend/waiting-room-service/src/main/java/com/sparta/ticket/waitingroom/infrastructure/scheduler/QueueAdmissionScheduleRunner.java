package com.sparta.ticket.waitingroom.infrastructure.scheduler;

import com.sparta.ticket.waitingroom.application.QueueAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 입장 처리를 대기열 전용 스레드 하나에서 고정 지연으로 돌린다. 앞 처리가 끝난 뒤 interval만큼 쉬므로 한 서버 안에서는 겹치지 않는다.
 * 처리가 예외를 던져도 ThreadPoolTaskScheduler가 기록하고 다음 처리를 이어 간다.
 */
@RequiredArgsConstructor
public class QueueAdmissionScheduleRunner implements SmartLifecycle {

    private final QueueSchedulerProperties properties;
    private final QueueAdmissionService admissionService;
    private ThreadPoolTaskScheduler scheduler;

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("queue-scheduler-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(admissionService::admitAll, properties.interval());
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
