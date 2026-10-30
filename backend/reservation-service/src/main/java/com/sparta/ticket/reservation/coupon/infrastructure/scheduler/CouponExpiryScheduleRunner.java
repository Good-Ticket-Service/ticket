package com.sparta.ticket.reservation.coupon.infrastructure.scheduler;

import com.sparta.ticket.reservation.coupon.application.UserCouponExpiryService;
import com.sparta.ticket.reservation.coupon.infrastructure.scheduler.CouponSchedulerProperties.Expiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 회원 쿠폰 만료 배치를 쿠폰 전용 스레드 하나에서 고정 지연으로 돌린다. 앞 실행이 끝난 뒤 interval만큼 쉬므로 실행이 겹치지 않는다.
 * 작업이 예외를 던져도 ThreadPoolTaskScheduler가 기록하고 다음 실행을 이어 간다.
 */
@Slf4j
@RequiredArgsConstructor
public class CouponExpiryScheduleRunner implements SmartLifecycle {

    private final CouponSchedulerProperties properties;
    private final UserCouponExpiryService expiryService;
    private ThreadPoolTaskScheduler scheduler;

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("coupon-scheduler-");
        scheduler.initialize();

        Expiry expiry = properties.expiry();
        scheduler.scheduleWithFixedDelay(
                () -> {
                    int expired = expiryService.expireDue(expiry.batchSize(), expiry.timeBudget());
                    // 바꾼 것이 있을 때만 남긴다. 주기마다 0건 로그가 쌓이지 않게 한다
                    if (expired > 0) {
                        log.info("기간이 지난 회원 쿠폰 {}건을 EXPIRED로 바꿨다.", expired);
                    }
                },
                expiry.interval());
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
