package com.sparta.ticket.payment.application.service.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CommittedObservationTest {
    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void committedValueIsNotRecordedBeforeCommitAndRollbackLeavesItAbsent() {
        var meters = new SimpleMeterRegistry();
        TransactionSynchronizationManager.initSynchronization();
        var observation = new CommittedObservation(meters, Clock.systemUTC());
        observation.counter("payment.test.completed");
        assertThat(meters.find("payment.test.completed").counter()).isNull();
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(meters.find("payment.test.completed").counter()).isNull();
    }

    @Test
    void brokenMetricRegistryDoesNotThrowFromCommittedTransaction() {
        var meters = mock(MeterRegistry.class);
        when(meters.counter("payment.test.completed")).thenThrow(new IllegalStateException("registry error"));
        TransactionSynchronizationManager.initSynchronization();
        var observation = new CommittedObservation(meters, Clock.systemUTC());
        observation.counter("payment.test.completed");
        observation.duration("payment.test.duration", LocalDateTime.now(Clock.systemUTC()));
        assertThatCode(() ->
                        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit()))
                .doesNotThrowAnyException();
    }
}
