package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.infrastructure.scheduler.BookingSchedulerProperties.HoldExpiry;
import com.sparta.ticket.reservation.booking.infrastructure.scheduler.BookingSchedulerProperties.PaymentReconcile;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 만료와 결과 확인을 각자의 스레드에서 고정 지연으로 돌린다. 앞 실행이 끝난 뒤 interval만큼 쉬므로 같은 작업은 겹치지 않는다.
 * 작업이 예외를 던져도 ThreadPoolTaskScheduler가 기록하고 다음 실행을 이어 간다.
 */
@RequiredArgsConstructor
public class BookingScheduleRunner implements SmartLifecycle {

    private final BookingSchedulerProperties properties;
    private final HoldExpiryService holdExpiryService;
    private final PaymentReconcileService paymentReconcileService;
    private ThreadPoolTaskScheduler scheduler;

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("booking-scheduler-");
        scheduler.initialize();

        HoldExpiry expiry = properties.holdExpiry();
        scheduler.scheduleWithFixedDelay(
                () -> holdExpiryService.expireDue(expiry.batchSize(), expiry.timeBudget()), expiry.interval());

        PaymentReconcile reconcile = properties.paymentReconcile();
        scheduler.scheduleWithFixedDelay(
                () -> paymentReconcileService.reconcile(
                        reconcile.threshold(), reconcile.stuckAfter(), reconcile.batchSize(), reconcile.timeBudget()),
                reconcile.interval());
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
