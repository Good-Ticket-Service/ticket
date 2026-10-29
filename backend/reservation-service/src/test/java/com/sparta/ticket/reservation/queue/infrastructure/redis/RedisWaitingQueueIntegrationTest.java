package com.sparta.ticket.reservation.queue.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 대기열 진입을 실제 Redis 7.4로 확인한다. Redis는 테스트끼리 공유하므로 테스트마다 다른 공연 ID를 쓴다.
 * 동시 요청은 모든 스레드가 CountDownLatch 하나를 기다렸다가 한꺼번에 출발한다.
 */
class RedisWaitingQueueIntegrationTest extends ReservationIntegrationTest {

    private static final AtomicLong PERFORMANCE_IDS = new AtomicLong(1_000_000);

    @Autowired
    private RedisWaitingQueue waitingQueue;

    @Autowired
    private StringRedisTemplate redis;

    private long newPerformanceId() {
        return PERFORMANCE_IDS.incrementAndGet();
    }

    @Test
    @DisplayName("먼저 선 회원이 1번, 다음 회원이 2번이다")
    void enterInOrder() {
        long performanceId = newPerformanceId();

        QueuePosition first = waitingQueue.enter(performanceId, 7L);
        QueuePosition second = waitingQueue.enter(performanceId, 3L);

        assertThat(first.position()).isEqualTo(1);
        assertThat(first.aheadCount()).isZero();
        assertThat(second.position()).isEqualTo(2);
        assertThat(second.aheadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 줄에 있으면 새 번호를 받지 않고 원래 위치를 돌려준다")
    void reenterKeepsPosition() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);

        QueuePosition again = waitingQueue.enter(performanceId, 7L);

        assertThat(again.position()).isEqualTo(1);
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(performanceId))).isEqualTo(2);
        assertThat(redis.opsForValue().get(QueueKeys.seq(performanceId))).isEqualTo("2");
    }

    @Test
    @DisplayName("줄을 서면 그 공연이 스케줄러가 도는 공연 목록에 들어간다")
    void registersPerformance() {
        long performanceId = newPerformanceId();

        waitingQueue.enter(performanceId, 7L);

        assertThat(redis.opsForSet().isMember(QueueKeys.PERFORMANCES, String.valueOf(performanceId)))
                .isTrue();
    }

    @Test
    @DisplayName("순번을 조회하면 지금 위치를 돌려주고, 앞사람이 빠지면 순번이 줄어든다")
    void findPosition() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);

        assertThat(waitingQueue.findPosition(performanceId, 3L)).contains(new QueuePosition(1));

        redis.opsForZSet().remove(QueueKeys.waiting(performanceId), "7");

        assertThat(waitingQueue.findPosition(performanceId, 3L)).contains(new QueuePosition(0));
    }

    @Test
    @DisplayName("순번을 조회하면 마지막 조회 시각이 Redis 시각으로 갱신된다")
    void findPositionRecordsSeen() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        long before = redis.execute(
                (RedisCallback<Long>) connection -> connection.serverCommands().time());

        waitingQueue.findPosition(performanceId, 7L);

        Double seen = redis.opsForZSet().score(QueueKeys.seen(performanceId), "7");
        assertThat(seen).isNotNull();
        assertThat(seen.longValue()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("줄에 없으면 비어 있고, 조회 시각도 남기지 않는다")
    void findPositionNotInQueue() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);

        assertThat(waitingQueue.findPosition(performanceId, 3L)).isEmpty();
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "3")).isNull();
    }

    @Test
    @DisplayName("대기를 취소하면 줄과 조회 시각에서 함께 빠지고, 뒤 회원의 순번이 당겨진다")
    void leave() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);
        waitingQueue.findPosition(performanceId, 7L);

        waitingQueue.leave(performanceId, 7L);

        assertThat(waitingQueue.findPosition(performanceId, 7L)).isEmpty();
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "7")).isNull();
        assertThat(waitingQueue.findPosition(performanceId, 3L)).contains(new QueuePosition(0));
    }

    @Test
    @DisplayName("줄에 없는 회원이 취소해도 오류 없이 끝나고 다른 회원은 그대로다")
    void leaveNotInQueue() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);

        waitingQueue.leave(performanceId, 3L);
        waitingQueue.leave(performanceId, 3L);

        assertThat(waitingQueue.findPosition(performanceId, 7L)).contains(new QueuePosition(0));
    }

    @Test
    @DisplayName("취소한 뒤 다시 진입하면 원래 자리가 아니라 맨 뒤에 선다")
    void reenterAfterLeave() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);

        waitingQueue.leave(performanceId, 7L);
        QueuePosition again = waitingQueue.enter(performanceId, 7L);

        assertThat(again.position()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 회원이 동시에 50번 진입해도 줄에는 한 번만 서고, 번호도 하나만 쓴다")
    void sameUserConcurrently() throws Exception {
        long performanceId = newPerformanceId();

        List<QueuePosition> results = runConcurrently(50, i -> waitingQueue.enter(performanceId, 7L));

        assertThat(results).allMatch(result -> result.position() == 1);
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(performanceId))).isEqualTo(1);
        assertThat(redis.opsForValue().get(QueueKeys.seq(performanceId))).isEqualTo("1");
    }

    @Test
    @DisplayName("서로 다른 회원 100명이 동시에 진입하면 순번이 1~100으로 겹치지 않는다")
    void differentUsersConcurrently() throws Exception {
        long performanceId = newPerformanceId();

        List<QueuePosition> results = runConcurrently(100, userId -> waitingQueue.enter(performanceId, userId));

        assertThat(results)
                .extracting(QueuePosition::position)
                .containsExactlyInAnyOrderElementsOf(
                        LongStream.rangeClosed(1, 100).boxed().toList());
    }

    /** 0부터 count-1까지의 값을 하나씩 넘겨 동시에 실행하고 결과를 모은다. */
    private static List<QueuePosition> runConcurrently(int count, LongFunction<QueuePosition> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<QueuePosition>> futures = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            long value = i;
            futures.add(executor.submit(() -> {
                start.await();
                return task.apply(value);
            }));
        }
        start.countDown();
        List<QueuePosition> results = new ArrayList<>();
        for (Future<QueuePosition> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();
        return results;
    }
}
