package com.sparta.ticket.reservation.coupon.infrastructure.scheduler;

import com.sparta.ticket.reservation.coupon.application.CouponIssueReconcileService;
import com.sparta.ticket.reservation.coupon.application.CouponIssueReconcileService.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 쿠폰 발급 판단 맞추는 작업(#323)을 전용 스레드 하나에서 고정 지연으로 돌린다. 앞 실행이 끝난 뒤 interval만큼 쉬므로 실행이 겹치지 않는다.
 * 만료 배치(CouponExpiryScheduleRunner)와 스레드를 나눈다. 만료 배치가 길어져도 맞추는 주기가 밀리지 않게 한다.
 * 작업이 예외를 던져도 ThreadPoolTaskScheduler가 기록하고 다음 실행을 이어 간다.
 */
@Slf4j
@RequiredArgsConstructor
public class CouponIssueReconcileScheduleRunner implements SmartLifecycle {

    private final CouponSchedulerProperties properties;
    private final CouponIssueReconcileService reconcileService;
    private ThreadPoolTaskScheduler scheduler;

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("coupon-reconcile-");
        scheduler.initialize();

        scheduler.scheduleWithFixedDelay(
                () -> {
                    Result result = reconcileService.reconcile();
                    // 바꾼 것이 있을 때만 남긴다. 주기마다 0건 로그가 쌓이지 않게 한다
                    if (result.hasChanges()) {
                        log.info("쿠폰 발급 판단을 DB에 맞췄다. {}", result);
                    }
                },
                properties.reconcile().interval());
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
