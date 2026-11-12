package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 한 인스턴스의 발행은 한 번에 하나만 돌아, 완료 전용 풀 연결을 발행 쪽에서 둘 이상 잡지 않는다. */
class OutboxPublisherTurnTest {

    @Test
    @DisplayName("커밋 직후 발행과 발행 스케줄러가 동시에 불러도 Outbox를 잠그는 트랜잭션은 한 번에 하나다")
    @SuppressWarnings("unchecked")
    void publishesOneAtATime() throws Exception {
        OutboxRowRepository outbox = mock(OutboxRowRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        invocation -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostInside = new AtomicInteger();
        when(outbox.lockOldest(anyInt())).thenAnswer(invocation -> {
            mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            Thread.sleep(200);
            inside.decrementAndGet();
            return List.of();
        });
        OutboxPublisher publisher = new OutboxPublisher(
                outbox, mock(KafkaTemplate.class), transactions, new BookingMetrics(new SimpleMeterRegistry()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                start.await();
                return publisher.publish(100, Duration.ofSeconds(1));
            });
            Future<?> second = pool.submit(() -> {
                start.await();
                return publisher.publish(100, Duration.ofSeconds(1));
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(mostInside.get()).isEqualTo(1);
    }
}
