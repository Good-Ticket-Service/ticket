package com.sparta.ticket.reservation.queue.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.QueueAdmission;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 대기열 진입을 실제 Redis 7.4로 확인한다. Redis는 테스트끼리 공유하므로 테스트마다 다른 공연 ID를 쓴다.
 * 동시 요청은 모든 스레드가 CountDownLatch 하나를 기다렸다가 한꺼번에 출발한다.
 */
class RedisWaitingQueueIntegrationTest extends ReservationIntegrationTest {

    private static final AtomicLong PERFORMANCE_IDS = new AtomicLong(1_000_000);

    /** 키의 만료 시각을 읽는 스크립트. 템플릿에 PEXPIRETIME 메서드가 없다. */
    private static final RedisScript<Long> EXPIRE_TIME =
            new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    @Autowired
    private RedisWaitingQueue waitingQueue;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private WaitingRoomRepository waitingRoomRepository;

    private long newPerformanceId() {
        return PERFORMANCE_IDS.incrementAndGet();
    }

    /** 줄에 서는 경우만 보는 테스트용. 입장해 있지 않은 회원의 진입 결과는 항상 줄 위치다. */
    private QueuePosition enterWaiting(long performanceId, long userId) {
        return (QueuePosition) waitingQueue.enter(performanceId, userId);
    }

    private long redisNowMillis() {
        return redis.execute(
                (RedisCallback<Long>) connection -> connection.serverCommands().time());
    }

    @Test
    @DisplayName("먼저 선 회원이 1번, 다음 회원이 2번이다")
    void enterInOrder() {
        long performanceId = newPerformanceId();

        QueuePosition first = enterWaiting(performanceId, 7L);
        QueuePosition second = enterWaiting(performanceId, 3L);

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

        QueuePosition again = enterWaiting(performanceId, 7L);

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
    @DisplayName("줄을 서면 마지막 조회 시각도 Redis 시각으로 남는다")
    void enterRecordsSeen() {
        long performanceId = newPerformanceId();
        long before = redisNowMillis();

        waitingQueue.enter(performanceId, 7L);

        Double seen = redis.opsForZSet().score(QueueKeys.seen(performanceId), "7");
        assertThat(seen).isNotNull();
        assertThat(seen.longValue()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("이미 줄에 있는 회원이 다시 진입하면 순번은 그대로이고 마지막 조회 시각만 갱신된다")
    void reenterRefreshesSeen() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        redis.opsForZSet().add(QueueKeys.seen(performanceId), "7", 0);

        waitingQueue.enter(performanceId, 7L);

        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "7")).isGreaterThan(0);
        assertThat(redis.opsForValue().get(QueueKeys.seq(performanceId))).isEqualTo("1");
    }

    @Test
    @DisplayName("순번을 조회하면 지금 위치를 돌려주고, 앞사람이 빠지면 순번이 줄어든다")
    void findPosition() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);

        assertThat(waitingQueue.find(performanceId, 3L)).contains(new QueuePosition(1));

        redis.opsForZSet().remove(QueueKeys.waiting(performanceId), "7");

        assertThat(waitingQueue.find(performanceId, 3L)).contains(new QueuePosition(0));
    }

    @Test
    @DisplayName("순번을 조회하면 마지막 조회 시각이 Redis 시각으로 갱신된다")
    void findPositionRecordsSeen() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        long before = redisNowMillis();

        waitingQueue.find(performanceId, 7L);

        Double seen = redis.opsForZSet().score(QueueKeys.seen(performanceId), "7");
        assertThat(seen).isNotNull();
        assertThat(seen.longValue()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("줄에 없으면 비어 있고, 조회 시각도 남기지 않는다")
    void findPositionNotInQueue() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);

        assertThat(waitingQueue.find(performanceId, 3L)).isEmpty();
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "3")).isNull();
    }

    @Test
    @DisplayName("입장해 있는 회원을 조회하면 입장 만료 시각을 돌려주고, 조회 시각은 남기지 않는다")
    void findAdmitted() {
        long performanceId = newPerformanceId();
        long expireAt = redisNowMillis() + 600_000;
        redis.opsForZSet().add(QueueKeys.active(performanceId), "7", expireAt);

        assertThat(waitingQueue.find(performanceId, 7L)).contains(new QueueAdmission(Instant.ofEpochMilli(expireAt)));
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "7")).isNull();
    }

    @Test
    @DisplayName("입장 만료가 지났으면 스케줄러가 아직 정리하지 않았어도 조회 결과는 비어 있다")
    void findExpiredAdmission() {
        long performanceId = newPerformanceId();
        redis.opsForZSet().add(QueueKeys.active(performanceId), "7", redisNowMillis() - 1);

        assertThat(waitingQueue.find(performanceId, 7L)).isEmpty();
    }

    @Test
    @DisplayName("입장해 있는 회원이 다시 진입하면 줄에 다시 서지 않고 입장 상태를 돌려준다")
    void enterWhileAdmitted() {
        long performanceId = newPerformanceId();
        long expireAt = redisNowMillis() + 600_000;
        redis.opsForZSet().add(QueueKeys.active(performanceId), "7", expireAt);

        assertThat(waitingQueue.enter(performanceId, 7L)).isEqualTo(new QueueAdmission(Instant.ofEpochMilli(expireAt)));
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(performanceId))).isZero();
        assertThat(redis.hasKey(QueueKeys.seq(performanceId))).isFalse();
    }

    @Test
    @DisplayName("입장 만료가 지난 회원이 진입하면 입장 목록에서 빠지고 줄 맨 뒤에 선다")
    void enterAfterAdmissionExpired() {
        long performanceId = newPerformanceId();
        enterWaiting(performanceId, 3L);
        redis.opsForZSet().add(QueueKeys.active(performanceId), "7", redisNowMillis() - 1);

        QueuePosition position = enterWaiting(performanceId, 7L);

        assertThat(position.position()).isEqualTo(2);
        assertThat(redis.opsForZSet().score(QueueKeys.active(performanceId), "7"))
                .isNull();
    }

    @Test
    @DisplayName("대기를 취소하면 줄과 조회 시각에서 함께 빠지고, 뒤 회원의 순번이 당겨진다")
    void leave() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);
        waitingQueue.find(performanceId, 7L);

        waitingQueue.leave(performanceId, 7L);

        assertThat(waitingQueue.find(performanceId, 7L)).isEmpty();
        assertThat(redis.opsForZSet().score(QueueKeys.seen(performanceId), "7")).isNull();
        assertThat(waitingQueue.find(performanceId, 3L)).contains(new QueuePosition(0));
    }

    @Test
    @DisplayName("줄에 없는 회원이 취소해도 오류 없이 끝나고 다른 회원은 그대로다")
    void leaveNotInQueue() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);

        waitingQueue.leave(performanceId, 3L);
        waitingQueue.leave(performanceId, 3L);

        assertThat(waitingQueue.find(performanceId, 7L)).contains(new QueuePosition(0));
    }

    @Test
    @DisplayName("취소한 뒤 다시 진입하면 원래 자리가 아니라 맨 뒤에 선다")
    void reenterAfterLeave() {
        long performanceId = newPerformanceId();
        waitingQueue.enter(performanceId, 7L);
        waitingQueue.enter(performanceId, 3L);

        waitingQueue.leave(performanceId, 7L);
        QueuePosition again = enterWaiting(performanceId, 7L);

        assertThat(again.position()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 회원이 동시에 50번 진입해도 줄에는 한 번만 서고, 번호도 하나만 쓴다")
    void sameUserConcurrently() throws Exception {
        long performanceId = newPerformanceId();

        List<QueuePosition> results = runConcurrently(50, i -> enterWaiting(performanceId, 7L));

        assertThat(results).allMatch(result -> result.position() == 1);
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(performanceId))).isEqualTo(1);
        assertThat(redis.opsForValue().get(QueueKeys.seq(performanceId))).isEqualTo("1");
    }

    @Test
    @DisplayName("서로 다른 회원 100명이 동시에 진입하면 순번이 1~100으로 겹치지 않는다")
    void differentUsersConcurrently() throws Exception {
        long performanceId = newPerformanceId();

        List<QueuePosition> results = runConcurrently(100, userId -> enterWaiting(performanceId, userId));

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

    /** 키의 만료 시각(ms). 만료가 없으면 -1, 키가 없으면 -2다. */
    private long expireTime(String key) {
        return redis.execute(EXPIRE_TIME, List.of(key));
    }

    @Test
    @DisplayName("줄 키(줄·번호·조회 시각·입장 목록·마지막 입장 시각)는 room 설정과 같은 시각에 만료된다")
    void expireWithRoom() {
        Instant now = Instant.now();
        Instant closeAt = now.plus(Duration.ofHours(1));
        long roomId = waitingRoomRepository
                .create(
                        List.of("schedule:" + newPerformanceId()),
                        now.minus(Duration.ofMinutes(1)),
                        closeAt,
                        closeAt.plus(Duration.ofMinutes(10)))
                .roomId();
        long keepUntil = expireTime(QueueKeys.roomConfig(roomId));

        waitingQueue.enter(roomId, 1L);
        waitingQueue.enter(roomId, 2L);
        waitingQueue.admit(
                roomId,
                new AdmissionPolicy(
                        1, Duration.ofSeconds(1), 1000, Duration.ofMinutes(2), Duration.ofMinutes(10), 1000));

        assertThat(keepUntil).isEqualTo(closeAt.plus(Duration.ofMinutes(10)).toEpochMilli());
        assertThat(List.of(
                        QueueKeys.waiting(roomId),
                        QueueKeys.seq(roomId),
                        QueueKeys.seen(roomId),
                        QueueKeys.active(roomId),
                        QueueKeys.lastAdmit(roomId)))
                .allSatisfy(key -> assertThat(expireTime(key)).as(key).isEqualTo(keepUntil));
    }
}
