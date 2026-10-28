package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.payment.ReconcileSchedule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReconcileRetryDelayTest {
    private PaymentReconcileProperties properties(double jitter) {
        return new PaymentReconcileProperties(List.of(1L, 5L, 15L, 60L, 1440L), 30, 30, 300, jitter, 5, 15, 50, 15);
    }

    @Test
    void doublesUntilCapAndExtremePersistedRetryCountDoesNotOverflow() {
        var delay = new ReconcileRetryDelay(properties(0), () -> 0.5);
        long[] seconds = {30, 60, 120, 240, 300, 300};
        for (int i = 0; i < seconds.length; i++) {
            assertThat(delay.afterUnknown(i + 1)).isEqualTo(Duration.ofSeconds(seconds[i]));
        }
        assertThat(delay.afterUnknown(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void jitterBoundsSpreadRetriesWithoutExceedingAbsoluteCap() {
        var low = new ReconcileRetryDelay(properties(0.2), () -> 0.0);
        var high = new ReconcileRetryDelay(properties(0.2), () -> 1.0);
        assertThat(low.afterUnknown(1)).isEqualTo(Duration.ofSeconds(24));
        assertThat(high.afterUnknown(1)).isEqualTo(Duration.ofSeconds(36));
        assertThat(low.afterUnknown(5)).isEqualTo(Duration.ofSeconds(240));
        assertThat(high.afterUnknown(5)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void invalidOrMutableSchedulesCannotChangeCheckpointMeaning() {
        for (List<Long> schedule : List.of(List.<Long>of(), List.of(0L), List.of(1L, 1L), List.of(5L, 1L))) {
            assertThatThrownBy(() -> new ReconcileSchedule(schedule)).isInstanceOf(IllegalArgumentException.class);
        }
        var input = new ArrayList<>(List.of(1L, 5L));
        var schedule = new ReconcileSchedule(input);
        input.set(0, 99L);
        assertThat(schedule.encode()).isEqualTo("1,5");
        assertThat(ReconcileSchedule.decode(schedule.encode())).isEqualTo(schedule);
        assertThatThrownBy(() -> properties(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springBindsDefaultAndCustomListsAndRejectsBadConfiguration() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(context -> assertThat(
                        context.getBean(PaymentReconcileProperties.class).checkpointsMinutes())
                .containsExactly(1L, 5L, 15L, 60L, 1440L));
        runner.withPropertyValues("payment.reconcile.checkpoints-minutes=2,8,30")
                .run(context -> assertThat(context.getBean(PaymentReconcileProperties.class)
                                .checkpointsMinutes())
                        .containsExactly(2L, 8L, 30L));
        runner.withPropertyValues("payment.reconcile.checkpoints-minutes=2,2")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.reconcile.retry-initial-seconds=301")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentReconcileProperties.class)
    static class Binding {}
}
