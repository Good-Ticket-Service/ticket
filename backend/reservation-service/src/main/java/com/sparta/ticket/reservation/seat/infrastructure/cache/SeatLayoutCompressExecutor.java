package com.sparta.ticket.reservation.seat.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * layout 본문을 뒤에서 gzip으로 압축하는 전용 스레드(#287 사전 압축). 처음 요청이 압축을 기다리지 않게 원본만 만들어 주고, 압축은 여기서 한다.
 * 스냅샷 refresh 스레드(SeatSnapshotRefreshExecutor)와 나눈다. 압축(20,000석 약 50ms)이 스냅샷 갱신을 늦추지 않게 하기 위해서다.
 *
 * Note: Executor를 구현하지 않는 래퍼다. Executor 타입 빈이 하나라도 있으면 Spring Boot가 기본 applicationTaskExecutor를
 * 만들지 않는다(SeatSnapshotRefreshExecutor와 같은 이유).
 */
public final class SeatLayoutCompressExecutor {

    /** 스레드 수. 압축은 회차·버전마다 한 번이라 하나면 충분하고, CPU를 압축에 몰아 쓰지 않게 묶는다. */
    static final int THREADS = 1;

    /**
     * 대기 작업 수 상한. 같은 키의 압축은 하나로 합치므로 동시에 처음 열리는 회차 수를 넘지 않는다. 넘치면 거절하고 횟수를 지표로 남긴다.
     * 거절되면 그 키는 원본으로 계속 주다가 다음 요청에서 다시 압축을 시도한다.
     */
    static final int QUEUE_CAPACITY = 16;

    private final Executor executor;
    private final Runnable shutdown;

    private SeatLayoutCompressExecutor(Executor executor, Runnable shutdown) {
        this.executor = executor;
        this.shutdown = shutdown;
    }

    /** 운영용 전용 스레드 seat-layout-compress. 거절한 작업 수를 reservation.seat.layout.compress.rejected에 남긴다. */
    public static SeatLayoutCompressExecutor dedicatedThread(MeterRegistry meters) {
        Counter rejected = Counter.builder("reservation.seat.layout.compress.rejected")
                .description("layout 압축 전용 스레드와 큐가 가득 차 거절한 작업 수")
                .register(meters);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                THREADS,
                THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "seat-layout-compress");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> {
                    rejected.increment();
                    throw new RejectedExecutionException("layout 압축 전용 스레드와 큐가 가득 찼습니다");
                });
        return new SeatLayoutCompressExecutor(pool, pool::shutdown);
    }

    /** 테스트용. 압축을 테스트가 정한 executor에서 실행해, 스레드를 기다리지 않고 압축 전·후를 나눠 본다. */
    public static SeatLayoutCompressExecutor using(Executor executor) {
        return new SeatLayoutCompressExecutor(executor, () -> {});
    }

    Executor executor() {
        return executor;
    }

    /** 빈을 없앨 때 부른다(destroyMethod). 대기 중인 압축은 마저 끝내고 새 작업은 거절한다. */
    public void shutdown() {
        shutdown.run();
    }
}
