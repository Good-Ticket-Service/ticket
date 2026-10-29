package com.sparta.ticket.payment.infrastructure.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PaymentObservationTest {
    @Test
    void scrapeDoesNotQueryAndFailureRetainsSnapshotThenRecoveryClearsOldGroups() {
        var reader = mock(PaymentObservationTransactions.class);
        var meters = new SimpleMeterRegistry();
        var tags = List.of("provider", "TOSS", "status", "REQUESTED", "mode", "SLOW", "reason", "AUTO_VOID");
        when(reader.read())
                .thenReturn(List.of(new PaymentObservationTransactions.Sample("payment.db.refunds.pending", tags, 7)))
                .thenThrow(new IllegalStateException("DB down"))
                .thenReturn(List.of());
        var observation = new PaymentObservation(
                reader, meters, Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC), new MockEnvironment());
        var pending = meters.get("payment.db.refunds.pending")
                .tags(tags.toArray(String[]::new))
                .gauge();
        assertThat(meters.get("payment.observation.last.success.timestamp.seconds")
                        .gauge()
                        .value())
                .isZero();
        observation.refresh();
        for (int i = 0; i < 100; i++) assertThat(pending.value()).isEqualTo(7);
        verify(reader, times(1)).read();
        observation.refresh();
        assertThat(pending.value()).isEqualTo(7);
        assertThat(meters.get("payment.observation.success").gauge().value()).isZero();
        assertThat(meters.get("payment.observation.last.success.timestamp.seconds")
                        .gauge()
                        .value())
                .isEqualTo(1000);
        observation.refresh();
        assertThat(pending.value()).isZero();
        assertThat(meters.get("payment.observation.success").gauge().value()).isEqualTo(1);
    }
}
