package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import com.sparta.ticket.reservation.booking.application.cancel.CancelReconcileService;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 만료, 결과 확인, 취소 확인 스케줄러를 켠다. 통합 테스트는 reservation.booking.scheduler.enabled=false로 끄고 서비스를 직접 부른다.
 * Note: @EnableScheduling을 쓰지 않는다. Boot 기본 스케줄러는 스레드가 하나라 결과 확인이 payment를 기다리는 동안 만료가 멈춘다.
 * booking 전용 스레드로 돌리되 TaskScheduler 빈으로 내놓지 않는다. 빈으로 내놓으면 다른 모듈의 @Scheduled가 이 스레드를 함께 쓴다.
 */
@Configuration
@EnableConfigurationProperties(BookingSchedulerProperties.class)
@ConditionalOnProperty(
        prefix = "reservation.booking.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BookingSchedulingConfig {

    @Bean
    public BookingScheduleRunner bookingScheduleRunner(
            BookingSchedulerProperties properties,
            HoldExpiryService holdExpiryService,
            PaymentReconcileService paymentReconcileService,
            CancelReconcileService cancelReconcileService) {
        return new BookingScheduleRunner(
                properties, holdExpiryService, paymentReconcileService, cancelReconcileService);
    }
}
