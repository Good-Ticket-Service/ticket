package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import com.sparta.ticket.reservation.booking.application.cancel.CancelReconcileService;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.application.reconcile.UnconfirmedRefundService;
import com.sparta.ticket.reservation.booking.infrastructure.scheduler.BookingSchedulerProperties.CancelReconcile;
import com.sparta.ticket.reservation.booking.infrastructure.scheduler.BookingSchedulerProperties.HoldExpiry;
import com.sparta.ticket.reservation.booking.infrastructure.scheduler.BookingSchedulerProperties.PaymentReconcile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 만료, 결과 확인, 취소 확인을 각자의 스레드에서 고정 지연으로 돌린다. 앞 실행이 끝난 뒤 interval만큼 쉬므로 같은 작업은 겹치지 않는다.
 * 작업이 예외를 던져도 ThreadPoolTaskScheduler가 기록하고 다음 실행을 이어 간다.
 */
@Slf4j
@RequiredArgsConstructor
public class BookingScheduleRunner implements SmartLifecycle {

    private final BookingSchedulerProperties properties;
    private final HoldExpiryService holdExpiryService;
    private final PaymentReconcileService paymentReconcileService;
    private final UnconfirmedRefundService unconfirmedRefundService;
    private final CancelReconcileService cancelReconcileService;
    private ThreadPoolTaskScheduler scheduler;

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        // Note: 결과 확인과 취소 확인은 payment를 기다린다. 작업마다 스레드를 하나씩 둬야 한쪽이 멈춰도 만료와 다른 쪽이 돈다
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("booking-scheduler-");
        scheduler.initialize();

        HoldExpiry expiry = properties.holdExpiry();
        scheduler.scheduleWithFixedDelay(
                () -> holdExpiryService.expireDue(expiry.batchSize(), expiry.timeBudget()), expiry.interval());

        PaymentReconcile reconcile = properties.paymentReconcile();
        // Note: 확정하지 못한 결제의 환불은 결과 확인 다음에 같은 스레드에서 돈다. 둘 다 결제 중 예약을 payment에 묻는 일이고,
        // 환불 대상은 결과 확인이 기록하므로 같은 주기면 충분하다. 스레드를 더 두지 않아 만료는 여전히 멈추지 않는다.
        scheduler.scheduleWithFixedDelay(
                () -> {
                    runIsolated(
                            "결과 확인",
                            () -> paymentReconcileService.reconcile(
                                    reconcile.threshold(),
                                    reconcile.stuckAfter(),
                                    reconcile.batchSize(),
                                    reconcile.timeBudget()));
                    runIsolated(
                            "확정 못 한 결제 환불",
                            () -> unconfirmedRefundService.refund(
                                    reconcile.threshold(), reconcile.batchSize(), reconcile.timeBudget()));
                },
                reconcile.interval());

        CancelReconcile cancel = properties.cancelReconcile();
        scheduler.scheduleWithFixedDelay(
                () -> cancelReconcileService.reconcile(cancel.threshold(), cancel.batchSize(), cancel.timeBudget()),
                cancel.interval());
    }

    /** 같은 주기에 묶은 작업 하나가 실패해도 다음 작업은 돈다. 실패한 작업은 다음 주기에 다시 돈다. */
    private static void runIsolated(String task, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("{} 실행에 실패했다. 다음 주기에 다시 돈다.", task, e);
        }
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
