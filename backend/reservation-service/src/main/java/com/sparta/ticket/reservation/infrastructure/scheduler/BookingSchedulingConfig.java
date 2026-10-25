package com.sparta.ticket.reservation.infrastructure.scheduler;

import com.sparta.ticket.reservation.application.HoldExpiryService;
import com.sparta.ticket.reservation.application.PaymentReconcileService;
import com.sparta.ticket.reservation.infrastructure.scheduler.BookingSchedulerProperties.HoldExpiry;
import com.sparta.ticket.reservation.infrastructure.scheduler.BookingSchedulerProperties.PaymentReconcile;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 만료와 결과 확인을 고정 지연으로 돌린다. 앞 실행이 끝난 뒤 interval만큼 쉬므로 한 인스턴스 안에서는 겹치지 않는다.
 * 통합 테스트는 reservation.booking.scheduler.enabled=false로 끄고 서비스를 직접 부른다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BookingSchedulerProperties.class)
@ConditionalOnProperty(
        prefix = "reservation.booking.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class BookingSchedulingConfig implements SchedulingConfigurer {

    private final BookingSchedulerProperties properties;
    private final HoldExpiryService holdExpiryService;
    private final PaymentReconcileService paymentReconcileService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        HoldExpiry expiry = properties.holdExpiry();
        registrar.addFixedDelayTask(
                () -> holdExpiryService.expireDue(expiry.batchSize(), expiry.timeBudget()), expiry.interval());

        PaymentReconcile reconcile = properties.paymentReconcile();
        registrar.addFixedDelayTask(
                () -> paymentReconcileService.reconcile(
                        reconcile.threshold(), reconcile.stuckAfter(), reconcile.batchSize(), reconcile.timeBudget()),
                reconcile.interval());
    }
}
