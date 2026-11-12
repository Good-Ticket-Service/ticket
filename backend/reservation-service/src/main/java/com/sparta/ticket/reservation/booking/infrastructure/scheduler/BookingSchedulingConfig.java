package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import com.sparta.ticket.reservation.booking.application.cancel.CancelReconcileService;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentBacklogService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.application.reconcile.UnconfirmedRefundService;
import com.sparta.ticket.reservation.booking.application.schedulecancel.ScheduleCancellationService;
import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxPublishWaker;
import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 만료, 결과 확인, 취소 확인, 회차 취소 정리 스케줄러를 켠다. 통합 테스트는 reservation.booking.scheduler.enabled=false로 끄고 서비스를 직접 부른다.
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
            UnconfirmedRefundService unconfirmedRefundService,
            CancelReconcileService cancelReconcileService,
            ScheduleCancellationService scheduleCancellationService,
            OutboxPublisher outboxPublisher,
            PaymentBacklogService paymentBacklogService) {
        return new BookingScheduleRunner(
                properties,
                holdExpiryService,
                paymentReconcileService,
                unconfirmedRefundService,
                cancelReconcileService,
                scheduleCancellationService,
                outboxPublisher,
                paymentBacklogService);
    }

    /**
     * 커밋 직후 발행. 발행 스케줄러와 같은 묶음 크기·보내기 대기를 쓴다. 스케줄러를 끄면(통합 테스트) 이것도 없다.
     * publish-on-commit=false로 끄면 승인 요청은 발행 스케줄러만 보낸다. 커밋 직후 발행에 문제가 생겼을 때 이미지를 바꾸지 않고
     * 되돌리는 스위치이고, 같은 이미지로 켜고 끈 지연을 비교할 때도 쓴다.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "reservation.booking.scheduler.outbox",
            name = "publish-on-commit",
            havingValue = "true",
            matchIfMissing = true)
    public OutboxPublishWaker outboxPublishWaker(
            BookingSchedulerProperties properties, OutboxPublisher outboxPublisher) {
        return new OutboxPublishWaker(
                outboxPublisher,
                properties.outbox().batchSize(),
                properties.outbox().sendTimeout());
    }
}
