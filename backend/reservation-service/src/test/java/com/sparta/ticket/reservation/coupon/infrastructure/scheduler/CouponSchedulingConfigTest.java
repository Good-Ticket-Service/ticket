package com.sparta.ticket.reservation.coupon.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.reservation.coupon.application.UserCouponExpiryService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 통합 테스트에서는 만료 배치 스케줄러가 꺼져 있으므로, 켜고 끄는 조건과 설정 검증은 DB 없는 작은 컨텍스트로 확인한다. */
class CouponSchedulingConfigTest {

    private final UserCouponExpiryService expiryService = mock(UserCouponExpiryService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(CouponSchedulingConfig.class)
            .withBean(UserCouponExpiryService.class, () -> expiryService);

    /** 켜고 만료 배치 설정값을 모두 넣은 컨텍스트. */
    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(
                "reservation.coupon.scheduler.enabled=true",
                "reservation.coupon.scheduler.expiry.interval=50ms",
                "reservation.coupon.scheduler.expiry.batch-size=100",
                "reservation.coupon.scheduler.expiry.time-budget=40ms");
    }

    @Test
    @DisplayName("enabled=true면 만료 배치를 설정값으로 주기마다 돌린다")
    void runsWithProperties() {
        enabled().run(context -> {
            assertThat(context).hasSingleBean(CouponExpiryScheduleRunner.class);
            verify(expiryService, timeout(2_000).atLeast(2)).expireDue(100, Duration.ofMillis(40));
        });
    }

    @Test
    @DisplayName("만료 배치가 실패해도 다음 주기에 다시 돈다")
    void keepsRunningAfterFailure() {
        when(expiryService.expireDue(anyInt(), any())).thenThrow(new IllegalStateException("db down"));

        enabled().run(context -> {
            verify(expiryService, timeout(2_000).atLeastOnce()).expireDue(anyInt(), any());
            clearInvocations(expiryService);
            verify(expiryService, timeout(2_000).atLeastOnce()).expireDue(anyInt(), any());
        });
    }

    @Test
    @DisplayName("enabled 설정이 없으면 스케줄러를 띄우지 않는다(통합 테스트가 이 상태로 돈다)")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CouponExpiryScheduleRunner.class);
        });
    }

    @Test
    @DisplayName("enabled=false면 스케줄러를 띄우지 않는다")
    void canBeDisabled() {
        enabled().withPropertyValues("reservation.coupon.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CouponExpiryScheduleRunner.class));
    }

    @Test
    @DisplayName("켰는데 설정값이 틀리거나 빠지면 기동에 실패한다")
    void rejectsInvalidValues() {
        enabled().withPropertyValues("reservation.coupon.scheduler.expiry.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("reservation.coupon.scheduler.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
