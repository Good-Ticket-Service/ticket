package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.sparta.ticket.reservation.booking.application.cancel.CancelReconcileService;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.application.reconcile.UnconfirmedRefundService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 통합 테스트는 스케줄러를 끄므로, 스케줄러 설정과 등록은 DB 없는 작은 컨텍스트로 확인한다. */
class BookingSchedulingConfigTest {

    private final HoldExpiryService holdExpiryService = mock(HoldExpiryService.class);
    private final PaymentReconcileService reconcileService = mock(PaymentReconcileService.class);
    private final UnconfirmedRefundService unconfirmedRefundService = mock(UnconfirmedRefundService.class);
    private final CancelReconcileService cancelReconcileService = mock(CancelReconcileService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(BookingSchedulingConfig.class)
            .withBean(HoldExpiryService.class, () -> holdExpiryService)
            .withBean(PaymentReconcileService.class, () -> reconcileService)
            .withBean(UnconfirmedRefundService.class, () -> unconfirmedRefundService)
            .withBean(CancelReconcileService.class, () -> cancelReconcileService)
            .withPropertyValues(
                    "reservation.booking.scheduler.hold-expiry.interval=50ms",
                    "reservation.booking.scheduler.hold-expiry.batch-size=100",
                    "reservation.booking.scheduler.hold-expiry.time-budget=40ms",
                    "reservation.booking.scheduler.payment-reconcile.interval=50ms",
                    "reservation.booking.scheduler.payment-reconcile.threshold=1m",
                    "reservation.booking.scheduler.payment-reconcile.stuck-after=30m",
                    "reservation.booking.scheduler.payment-reconcile.batch-size=20",
                    "reservation.booking.scheduler.payment-reconcile.time-budget=40ms",
                    "reservation.booking.scheduler.cancel-reconcile.interval=50ms",
                    "reservation.booking.scheduler.cancel-reconcile.threshold=2m",
                    "reservation.booking.scheduler.cancel-reconcile.batch-size=20",
                    "reservation.booking.scheduler.cancel-reconcile.time-budget=40ms");

    @Test
    @DisplayName("컨텍스트가 뜨면 만료, 결과 확인과 확정 못 한 결제의 환불, 취소 확인을 설정값으로 돌린다")
    void runsBothTasks() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BookingScheduleRunner.class);
            verify(holdExpiryService, timeout(2_000).atLeastOnce()).expireDue(100, Duration.ofMillis(40));
            verify(reconcileService, timeout(2_000).atLeastOnce())
                    .reconcile(
                            eq(Duration.ofMinutes(1)), eq(Duration.ofMinutes(30)), anyInt(), eq(Duration.ofMillis(40)));
            verify(unconfirmedRefundService, timeout(2_000).atLeastOnce())
                    .refund(eq(Duration.ofMinutes(1)), anyInt(), eq(Duration.ofMillis(40)));
            verify(cancelReconcileService, timeout(2_000).atLeastOnce())
                    .reconcile(Duration.ofMinutes(2), 20, Duration.ofMillis(40));
        });
    }

    @Test
    @DisplayName("결과 확인이 실패해도 같은 주기의 확정 못 한 결제 환불은 돈다")
    void refundRunsWhenReconcileFails() {
        doThrow(new IllegalStateException("db down")).when(reconcileService).reconcile(any(), any(), anyInt(), any());

        runner.run(context ->
                verify(unconfirmedRefundService, timeout(2_000).atLeastOnce()).refund(any(), anyInt(), any()));
    }

    @Test
    @DisplayName("결과 확인이 payment를 기다리며 멈춰 있어도 만료는 계속 돈다")
    void expiryKeepsRunningWhileReconcileBlocks() throws Exception {
        CountDownLatch reconcileStarted = new CountDownLatch(1);
        CountDownLatch paymentResponds = new CountDownLatch(1);
        doAnswer(invocation -> {
                    reconcileStarted.countDown();
                    paymentResponds.await(5, TimeUnit.SECONDS);
                    return 0;
                })
                .when(reconcileService)
                .reconcile(any(), any(), anyInt(), any());
        try {
            runner.run(context -> {
                assertThat(reconcileStarted.await(2, TimeUnit.SECONDS)).isTrue();
                clearInvocations(holdExpiryService);
                verify(holdExpiryService, timeout(2_000).atLeast(3)).expireDue(anyInt(), any());
            });
        } finally {
            paymentResponds.countDown();
        }
    }

    @Test
    @DisplayName("결과 확인과 취소 확인이 둘 다 payment를 기다리며 멈춰 있어도 만료는 계속 돈다")
    void expiryKeepsRunningWhileBothReconcilesBlock() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch paymentResponds = new CountDownLatch(1);
        doAnswer(invocation -> {
                    bothStarted.countDown();
                    paymentResponds.await(5, TimeUnit.SECONDS);
                    return 0;
                })
                .when(reconcileService)
                .reconcile(any(), any(), anyInt(), any());
        doAnswer(invocation -> {
                    bothStarted.countDown();
                    paymentResponds.await(5, TimeUnit.SECONDS);
                    return 0;
                })
                .when(cancelReconcileService)
                .reconcile(any(), anyInt(), any());
        try {
            runner.run(context -> {
                assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
                clearInvocations(holdExpiryService);
                verify(holdExpiryService, timeout(2_000).atLeast(3)).expireDue(anyInt(), any());
            });
        } finally {
            paymentResponds.countDown();
        }
    }

    @Test
    @DisplayName("설정값이 틀리면 기동에 실패한다")
    void rejectsInvalidValues() {
        runner.withPropertyValues("reservation.booking.scheduler.hold-expiry.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("enabled=false면 스케줄러를 띄우지 않는다")
    void canBeDisabled() {
        runner.withPropertyValues("reservation.booking.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BookingScheduleRunner.class));
    }
}
