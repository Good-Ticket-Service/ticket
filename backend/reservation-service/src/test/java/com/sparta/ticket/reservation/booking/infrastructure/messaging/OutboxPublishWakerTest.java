package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxPublishWakerTest {

    private static final int BATCH = 3;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(1);

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final AtomicInteger runs = new AtomicInteger();
    private OutboxPublishWaker waker;

    @AfterEach
    void stop() {
        if (waker != null) {
            waker.stop();
        }
    }

    private void publisherAnswers(IntSupplier answer) {
        when(publisher.publish(anyInt(), any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            return answer.getAsInt();
        });
        waker = new OutboxPublishWaker(publisher, BATCH, SEND_TIMEOUT);
        waker.start();
    }

    @Test
    @DisplayName("발행하는 동안 들어온 깨우기 여러 번은 한 번 더 도는 것으로 합친다")
    void coalescesWakesDuringRun() throws Exception {
        CountDownLatch inFirstRun = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        publisherAnswers(() -> {
            if (runs.get() == 1) {
                inFirstRun.countDown();
                waitFor(release);
            }
            return 0;
        });

        waker.wake();
        inFirstRun.await();
        for (int i = 0; i < 50; i++) {
            waker.wake();
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() == 2);
        Thread.sleep(200);
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("묶음을 꽉 채워 보냈으면 남은 행이 있을 수 있어 덜 찰 때까지 이어서 보낸다")
    void keepsPublishingWhileBatchesAreFull() {
        int[] answers = {BATCH, BATCH, 1};
        publisherAnswers(() -> answers[Math.min(runs.get(), answers.length) - 1]);

        waker.wake();

        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() == 3);
    }

    @Test
    @DisplayName("발행이 예외로 끝나도 다음 깨우기에는 다시 발행한다")
    void wakesAgainAfterException() {
        publisherAnswers(() -> {
            if (runs.get() == 1) {
                throw new IllegalStateException("broker");
            }
            return 0;
        });

        waker.wake();
        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() == 1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            waker.wake();
            assertThat(runs.get()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    @DisplayName("멈추면 하던 발행을 끝낸 뒤 돌아오고, 그 뒤 깨우기는 발행하지 않는다")
    void stopsAfterRunningPublishFinishes() throws Exception {
        CountDownLatch inRun = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        publisherAnswers(() -> {
            inRun.countDown();
            sleepIgnoringInterrupt(300);
            finished.set(true);
            return 0;
        });
        waker.wake();
        inRun.await();

        waker.stop();

        assertThat(finished).isTrue();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(waker.isRunning()).isFalse();
        waker.wake();
        Thread.sleep(200);
        assertThat(runs.get()).isEqualTo(1);
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 브로커 응답을 기다리는 보내기처럼, 멈추라는 인터럽트를 받아도 정한 시간까지 끝나지 않는다. */
    private static void sleepIgnoringInterrupt(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        boolean interrupted = false;
        while (System.nanoTime() < end) {
            try {
                Thread.sleep(Math.max(1, (end - System.nanoTime()) / 1_000_000));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
