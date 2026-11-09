package com.sparta.ticket.reservation.seat.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 좌석 배치도 스냅샷을 뒤에서 다시 만드는(refresh) 전용 스레드(#311). Caffeine의 정리 작업(만료·상한 초과 제거)도 이 스레드에서 돈다.
 *
 * Note: Executor를 구현하지 않는 래퍼다. Executor 타입 빈이 하나라도 있으면 Spring Boot가 기본 applicationTaskExecutor를
 * 만들지 않는다(TaskExecutorConfigurations.OnExecutorCondition). 이 스레드는 캐시 전용이라 다른 곳에서 주입받지 못하게 감춘다.
 */
public final class SeatSnapshotRefreshExecutor {

    /**
     * 스레드 수. 측정으로 조정하는 값이 아니라 refresh가 동시에 쓰는 DB 연결 수(Hikari 최대 10)를 묶는 안전장치라 상수로 둔다.
     * 동시에 다시 만드는 스냅샷 수도 이 값으로 묶여, refresh 중 캐시 밖에 잠깐 더 있는 스냅샷도 최대 이만큼이다(MAX_CACHED_SEATS 참고).
     */
    static final int THREADS = 2;

    /**
     * 대기 작업 수 상한. 같은 회차의 refresh는 Caffeine이 하나로 합치므로 대기 작업 수는 동시에 갱신할 회차 수를 넘지 않는다.
     * 넘치면 AbortPolicy처럼 거절하고 횟수를 지표로 남긴다. 거절되면 Caffeine이 기존 스냅샷을 그대로 주고 다음 요청에서 다시 시도하며,
     * cache-max-age(5초)가 지나면 요청이 직접 로딩한다. 정리 작업이 거절되면 Caffeine이 호출한 스레드에서 실행한다.
     */
    static final int QUEUE_CAPACITY = 16;

    private final Executor executor;
    private final Runnable shutdown;

    private SeatSnapshotRefreshExecutor(Executor executor, Runnable shutdown) {
        this.executor = executor;
        this.shutdown = shutdown;
    }

    /**
     * 운영용 전용 스레드 seat-snapshot-refresh-N. 거절한 작업 수를 reservation.seat.snapshot.refresh.rejected에 남긴다.
     *
     * Note: 거절될 때마다 Caffeine이 WARNING 로그를 남긴다. 로그를 끄지 않고 횟수를 지표로 남겨, 포화 여부를 로그 대신 지표로 본다.
     * 정리 작업도 같은 스레드를 쓰므로 그 거절도 함께 센다. 정리 작업은 실행 전에는 다음 작업을 넣지 않아 거의 refresh 거절이다.
     */
    public static SeatSnapshotRefreshExecutor dedicatedThreads(MeterRegistry meters) {
        Counter rejected = Counter.builder("reservation.seat.snapshot.refresh.rejected")
                .description("좌석 배치도 스냅샷 refresh 전용 스레드와 큐가 가득 차 거절한 작업 수(Caffeine 정리 작업 포함)")
                .register(meters);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                THREADS,
                THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                namedThreads(),
                (task, executor) -> {
                    // AbortPolicy와 같이 던진다. Caffeine이 받아 기존 스냅샷을 그대로 준다
                    rejected.increment();
                    throw new RejectedExecutionException("좌석 스냅샷 refresh 전용 스레드와 큐가 가득 찼습니다");
                });
        return new SeatSnapshotRefreshExecutor(pool, pool::shutdown);
    }

    /** 테스트용. refresh와 정리 작업을 테스트가 정한 executor에서 실행해, 스레드를 기다리지 않고 확인한다. 종료할 것이 없다. */
    public static SeatSnapshotRefreshExecutor using(Executor executor) {
        return new SeatSnapshotRefreshExecutor(executor, () -> {});
    }

    Executor executor() {
        return executor;
    }

    /** 빈을 없앨 때 부른다(destroyMethod). 대기 중인 refresh는 마저 끝내고 새 작업은 거절한다. */
    public void shutdown() {
        shutdown.run();
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "seat-snapshot-refresh-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
