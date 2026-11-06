package com.sparta.ticket.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 모든 {@code @Scheduled} 잡은 TaskScheduler 하나를 함께 쓴다. Boot 기본 스레드는 1개라, 정산 대조·정산서 발행처럼 오래 걸리는
 * 잡이 도는 동안 결제 Recovery(결과 1분 확정)·환불 Recovery가 멈춘다. 잡 하나가 붙잡혀도 다른 잡이 도는지 본다.
 * Note: 별도 설정을 쓰면 컨텍스트가 새로 떠 schema.sql이 중복 실행되므로, 공통 컨텍스트의 스케줄러에 직접 작업을 건다.
 */
class SchedulerIsolationIntegrationTest extends PaymentIntegrationTest {

    @Autowired
    private TaskScheduler scheduler;

    @Test
    @DisplayName("스케줄러 스레드는 잡 수만큼 있다")
    void poolCoversEveryScheduledJob() {
        // Note: 운영 @Scheduled 메서드는 10개다(정산 대조는 기동 1회·매일 2개). 잡을 늘리면 pool.size도 함께 늘린다.
        assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        // Note: getPoolSize()는 지금까지 만든 스레드 수라, 설정한 상한은 core 크기로 본다.
        assertThat(((ThreadPoolTaskScheduler) scheduler)
                        .getScheduledThreadPoolExecutor()
                        .getCorePoolSize())
                .isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("한 잡이 오래 붙잡혀도 다른 잡은 계속 실행된다")
    void longJobDoesNotBlockOthers() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ticks = new CountDownLatch(3);

        ScheduledFuture<?> blocker = scheduler.schedule(
                () -> {
                    blockerStarted.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                Instant.now());
        ScheduledFuture<?> ticker = null;
        try {
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            ticker = scheduler.scheduleWithFixedDelay(ticks::countDown, Duration.ofMillis(50));

            assertThat(ticks.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            blocker.cancel(false);
            if (ticker != null) ticker.cancel(false);
        }
    }
}
