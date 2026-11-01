package com.sparta.ticket.reservation.queue.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 입장 처리(admit.lua)를 실제 Redis 7.4로 확인한다. 입장 처리를 직접 부른다.
 * Redis는 테스트끼리 공유하므로 테스트마다 다른 공연 ID를 쓴다(다른 대기열 테스트와 겹치지 않는 3,000,000번대).
 */
class RedisAdmissionIntegrationTest extends ReservationIntegrationTest {

    private static final AtomicLong PERFORMANCE_IDS = new AtomicLong(3_000_000);

    @Autowired
    private RedisWaitingQueue waitingQueue;

    @Autowired
    private StringRedisTemplate redis;

    private long newPerformanceId() {
        return PERFORMANCE_IDS.incrementAndGet();
    }

    /** 초당 입장 인원과 상한만 바꾸고 나머지는 운영 임시값과 같게 둔다. */
    private static AdmissionPolicy policy(int admitPerSecond, int maxActive) {
        return new AdmissionPolicy(
                admitPerSecond, Duration.ofSeconds(1), maxActive, Duration.ofMinutes(2), Duration.ofMinutes(10), 1000);
    }

    private void enterAll(long performanceId, int count) {
        for (long userId = 1; userId <= count; userId++) {
            waitingQueue.enter(performanceId, userId);
        }
    }

    @Test
    @DisplayName("줄 앞부터 초당 입장 인원만큼 입장시키고, 입장한 회원은 줄과 조회 시각에서 빠진다")
    void admitsInOrderUpToRate() {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 15);

        AdmissionResult result = waitingQueue.admit(performanceId, policy(10, 1000));

        assertThat(result.admitted()).isEqualTo(10);
        assertThat(redis.opsForZSet().range(QueueKeys.active(performanceId), 0, -1))
                .containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(redis.opsForZSet().range(QueueKeys.waiting(performanceId), 0, -1))
                .containsExactly("11", "12", "13", "14", "15");
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "1")).isNull();
    }

    @Test
    @DisplayName("바로 다시 처리하면 흐른 시간만큼만 입장시킨다")
    void admitsOnlyForElapsedTime() {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 30);
        waitingQueue.admit(performanceId, policy(10, 1000));

        AdmissionResult again = waitingQueue.admit(performanceId, policy(10, 1000));

        // 두 처리 사이에 흐른 시간은 100ms보다 짧아야 정상이지만, 느린 환경을 생각해 한 명까지 허용한다
        assertThat(again.admitted()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("입장 인원 상한이 차면 속도가 남아도 더 입장시키지 않는다")
    void respectsMaxActive() {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 10);

        AdmissionResult first = waitingQueue.admit(performanceId, policy(100, 3));
        AdmissionResult second = waitingQueue.admit(performanceId, policy(100, 3));

        assertThat(first.admitted()).isEqualTo(3);
        assertThat(second.admitted()).isZero();
        assertThat(redis.opsForZSet().zCard(QueueKeys.active(performanceId))).isEqualTo(3);
    }

    @Test
    @DisplayName("입장 유효 시간이 지난 입장자는 빠지고 그 자리만큼 다음 대기자가 들어온다")
    void expiredEntriesFreeSeats() {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 5);
        waitingQueue.admit(performanceId, policy(100, 2));
        redis.opsForZSet().add(QueueKeys.active(performanceId), "1", 0);

        AdmissionResult result = waitingQueue.admit(performanceId, policy(100, 2));

        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.admitted()).isEqualTo(1);
        assertThat(redis.opsForZSet().range(QueueKeys.active(performanceId), 0, -1))
                .containsExactlyInAnyOrder("2", "3");
    }

    @Test
    @DisplayName("조회가 이탈 기준보다 오래 끊긴 대기자는 입장시키지 않고 줄에서 뺀다")
    void removesLeaversBeforeAdmitting() {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 3);
        redis.opsForZSet().add(QueueKeys.seen(performanceId), "1", 0);

        AdmissionResult result = waitingQueue.admit(performanceId, policy(100, 1000));

        assertThat(result.left()).isEqualTo(1);
        assertThat(result.admitted()).isEqualTo(2);
        assertThat(redis.opsForZSet().range(QueueKeys.active(performanceId), 0, -1))
                .containsExactlyInAnyOrder("2", "3");
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "1")).isNull();
    }

    @Test
    @DisplayName("한 번에 정리하지 못한 이탈자가 줄 앞에 남아 있어도 입장시키지 않고 뺀다")
    void skipsLeaversLeftAtFront() {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 3);
        redis.opsForZSet().add(QueueKeys.seen(performanceId), "1", 0);
        redis.opsForZSet().add(QueueKeys.seen(performanceId), "2", 1);
        // 한 번에 1명만 정리하므로 1번만 미리 빠지고, 2번은 줄 앞에 남은 채로 꺼내진다
        AdmissionPolicy onePerCleanup =
                new AdmissionPolicy(100, Duration.ofSeconds(1), 1000, Duration.ofMinutes(2), Duration.ofMinutes(10), 1);

        AdmissionResult result = waitingQueue.admit(performanceId, onePerCleanup);

        assertThat(result.left()).isEqualTo(2);
        assertThat(result.admitted()).isEqualTo(1);
        assertThat(redis.opsForZSet().range(QueueKeys.active(performanceId), 0, -1))
                .containsExactly("3");
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "2")).isNull();
    }

    @Test
    @DisplayName("입장 처리가 동시에 여러 번 돌아도 초당 입장 인원을 넘지 않는다")
    void concurrentAdmissionsRespectRate() throws Exception {
        long performanceId = newPerformanceId();
        enterAll(performanceId, 100);

        long total = runConcurrently(20, () -> waitingQueue.admit(performanceId, policy(10, 1000)));

        // 처음 1초분 10명 + 동시에 도는 동안 흐른 시간만큼. 느린 환경을 생각해 한 명까지 허용한다
        assertThat(total).isBetween(10L, 11L);
        assertThat(redis.opsForZSet().zCard(QueueKeys.active(performanceId))).isEqualTo(total);
    }

    /** 모든 스레드가 함께 출발해 입장 처리를 부르고, 입장시킨 인원을 더한다. */
    private static long runConcurrently(int count, Supplier<AdmissionResult> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AdmissionResult>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.get();
            }));
        }
        start.countDown();
        long total = 0;
        for (Future<AdmissionResult> future : futures) {
            total += future.get().admitted();
        }
        executor.shutdown();
        return total;
    }
}
