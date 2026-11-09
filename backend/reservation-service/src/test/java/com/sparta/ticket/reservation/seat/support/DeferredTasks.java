package com.sparta.ticket.reservation.seat.support;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 받은 작업을 쌓아 두었다가 테스트가 {@link #runPending()}을 부를 때 실행한다. 좌석 배치도 캐시의 refresh executor로 넘긴다(#311).
 * refresh가 아직 끝나지 않은 순간(첫 요청은 이전 스냅샷)과 끝난 뒤(다음 요청은 새 스냅샷)를 스레드를 기다리지 않고 나눠 본다.
 *
 * Note: 같은 스레드에서 바로 실행하면(Runnable::run) Caffeine이 refresh를 그 자리에서 끝내고 첫 요청에 새 스냅샷을 돌려줘
 * "refresh 뒤 첫 요청은 이전 스냅샷"을 확인할 수 없다. Caffeine 정리 작업도 쌓이지만, 실행 전에는 다음 정리 작업을 더 넣지 않고
 * 만료는 읽을 때도 확인하므로 결과는 같다.
 * Note: Executor를 구현하지 않는다. 통합 테스트에서 빈으로 두므로, Executor 빈이 생기면 Boot 기본 applicationTaskExecutor가 빠진다.
 */
public class DeferredTasks {

    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

    public void execute(Runnable task) {
        pending.add(task);
    }

    /** 쌓인 작업을 모두 실행한다. 실행 중에 새로 들어온 작업도 실행한다. */
    public void runPending() {
        Runnable task;
        while ((task = pending.poll()) != null) {
            task.run();
        }
    }
}
