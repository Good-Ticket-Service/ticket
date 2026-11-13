package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import com.sparta.ticket.reservation.booking.application.payment.StrandedConfirmDrain;
import com.sparta.ticket.reservation.booking.infrastructure.persistence.CompletionPool;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 스레드 concurrency개가 {@link StrandedConfirmDrain#sendNext()}를 되풀이한다. 할 일이 없으면 200ms 쉰다. 라우터는 메모리 값이라
 * 메시지 승인 중에는 DB를 읽지 않고, 동기로 돌리는 동안의 고르기는 작은 Outbox를 읽어 가볍다. 고른 요청을 곧바로 보내므로
 * 쉬는 동안 보낼 차례가 된 요청은 200ms 안에 나간다.
 *
 * <p>고르기와 결과 반영은 완료 전용 풀을 쓴다. 사용자 요청이 몰려 기본 풀이 찼을 때도 묶인 요청을 끝내기 위해서다.
 *
 * <p>Outbox 발행과 같은 단계에서 멈춘다. 데이터소스가 닫히기 전에 하던 일을 끝내기 위해서다. 보내던 요청은 resendAfter 안에
 * 답이 오므로 그만큼 기다리고, 넘으면 끊는다. 끊긴 요청은 보낸 시각이 남아 다음 기동이나 다른 인스턴스가 resendAfter 뒤에 다시 보낸다.
 */
@Slf4j
public class StrandedConfirmDrainWorkers implements SmartLifecycle {

    private static final Duration IDLE = Duration.ofMillis(200);

    private final StrandedConfirmDrain drain;
    private final int concurrency;
    private final Duration stopWait;
    private ExecutorService workers;
    private volatile boolean running;

    public StrandedConfirmDrainWorkers(StrandedConfirmDrain drain, int concurrency, Duration resendAfter) {
        this.drain = drain;
        this.concurrency = concurrency;
        this.stopWait = resendAfter.plusSeconds(1);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        AtomicInteger number = new AtomicInteger();
        workers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "booking-confirm-drain-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        for (int i = 0; i < concurrency; i++) {
            workers.execute(this::loop);
        }
    }

    private void loop() {
        while (running) {
            if (!sendNext()) {
                try {
                    Thread.sleep(IDLE.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 한 번 실패해도 작업자는 멈추지 않는다. 쉬었다가 다시 고른다. */
    private boolean sendNext() {
        try {
            return CompletionPool.call(drain::sendNext);
        } catch (RuntimeException e) {
            log.warn("Outbox에 남은 승인 요청을 대신 보내지 못했다. 잠시 뒤 다시 고른다", e);
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(stopWait.toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
