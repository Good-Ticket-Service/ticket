package com.sparta.ticket.payment.application.service.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class JobObservationTest {
    @Test
    void emptySuccessInternalFailureAndUncaughtFailureHaveDifferentHealth() {
        var meters = new SimpleMeterRegistry();
        var job = new JobObservation(meters, "refund");
        assertThat(meters.get("payment.job.last.success.timestamp.seconds")
                        .gauge()
                        .value())
                .isZero();
        job.run(() ->
                assertThat(meters.get("payment.job.running").gauge().value()).isEqualTo(1));
        assertThat(meters.get("payment.job.last.success.timestamp.seconds")
                        .gauge()
                        .value())
                .isPositive();
        job.run(job::failed);
        assertThatThrownBy(() -> job.run(() -> {
                    throw new IllegalStateException("DB down");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("payment.job.running").gauge().value()).isZero();
        assertThat(meters.get("payment.job.runs")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("payment.job.runs")
                        .tag("outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(2);
    }
}
