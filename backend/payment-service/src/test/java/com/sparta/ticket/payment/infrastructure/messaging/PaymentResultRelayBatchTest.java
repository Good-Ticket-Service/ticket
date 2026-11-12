package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc.Delivery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/** #384 묶음 송신·일괄 기록·연속 회차의 경계. 실제 DB·broker는 통합 시험이 맡는다. */
class PaymentResultRelayBatchTest {
    private final PaymentResultOutboxJdbc outbox = mock(PaymentResultOutboxJdbc.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private static List<Delivery> deliveries(UUID token, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Delivery(UUID.randomUUID(), "r" + i, "{\"i\":" + i + "}", token, 1))
                .toList();
    }

    private PaymentResultRelay relay(int batch, int rounds) {
        return new PaymentResultRelay(
                outbox,
                kafka,
                new PaymentResultRelayProperties(
                        batch,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        rounds),
                meters);
    }

    @Test
    void sendsWholeBatchBeforeWaitingAndRecordsOnlyAcknowledged() {
        UUID token = UUID.randomUUID();
        var batch = deliveries(token, 4);
        when(outbox.claim(anyInt(), any())).thenReturn(batch);
        var sentBeforeAnyWait = new ArrayList<String>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            sentBeforeAnyWait.add(call.getArgument(1));
            return call.getArgument(1).equals("r1")
                    ? CompletableFuture.failedFuture(new IllegalStateException("broker timeout"))
                    : CompletableFuture.completedFuture(null);
        });
        when(outbox.published(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        assertThat(relay(4, 1).publishOnce()).isEqualTo(3);

        assertThat(sentBeforeAnyWait).containsExactly("r0", "r1", "r2", "r3");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Delivery>> recorded = ArgumentCaptor.forClass(List.class);
        verify(outbox).published(recorded.capture());
        assertThat(recorded.getValue()).containsExactly(batch.get(0), batch.get(2), batch.get(3));
        verify(outbox).failed(eq(batch.get(1)), eq("DELIVERY_UNCERTAIN"), any());
        verify(outbox, never()).published(any(Delivery.class));
        assertThat(meters.counter("payment.result.outbox.published").count()).isEqualTo(3);
        assertThat(meters.counter("payment.result.delivery.failures").count()).isEqualTo(1);
    }

    @Test
    void doesNotRecordWhenNothingAcknowledged() {
        when(outbox.claim(anyInt(), any())).thenReturn(deliveries(UUID.randomUUID(), 2));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        assertThat(relay(2, 1).publishOnce()).isZero();

        verify(outbox, never()).published(anyList());
        verify(outbox, times(2)).failed(any(), eq("DELIVERY_UNCERTAIN"), any());
    }

    @Test
    void tickChainsFullBatchesAndStopsAtPartialOne() {
        UUID token = UUID.randomUUID();
        when(outbox.claim(anyInt(), any()))
                .thenReturn(deliveries(token, 3), deliveries(token, 3), deliveries(token, 1), deliveries(token, 3));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(outbox.published(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        relay(3, 50).tick();

        verify(outbox, times(3)).claim(anyInt(), any());
        verify(outbox, times(1)).pending();
        assertThat(meters.counter("payment.result.outbox.published").count()).isEqualTo(7);
    }

    @Test
    void tickStopsChainingWhenBrokerAcknowledgesNothing() {
        UUID token = UUID.randomUUID();
        when(outbox.claim(anyInt(), any())).thenReturn(deliveries(token, 2));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        relay(2, 50).tick();

        verify(outbox, times(1)).claim(anyInt(), any());
        verify(outbox, times(2)).failed(any(), eq("DELIVERY_UNCERTAIN"), any());
        verify(outbox, times(1)).pending();
    }

    @Test
    void tickHonoursRoundLimitWhileQueueStaysFull() {
        UUID token = UUID.randomUUID();
        when(outbox.claim(anyInt(), any())).thenReturn(deliveries(token, 2));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(outbox.published(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        relay(2, 4).tick();

        verify(outbox, times(4)).claim(anyInt(), any());
    }
}
