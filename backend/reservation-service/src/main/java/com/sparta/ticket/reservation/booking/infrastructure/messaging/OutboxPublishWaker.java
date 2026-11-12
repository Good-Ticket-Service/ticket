package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 승인 요청이 커밋되면 전용 스레드 하나에서 발행기를 바로 돌린다. 발행 스케줄러는 주기마다 Outbox를 보므로, 커밋 직후 요청은
 * 다음 차례까지 기다렸다(200ms 주기면 평균 0.1초). 보내는 방식은 발행 스케줄러와 같은 {@link OutboxPublisher}라서
 * 잠근 행만 보내고 받았다는 답을 받은 행만 지우는 규칙이 그대로다. 둘이 동시에 불러도 발행기가 차례로 돌리고 같은 행을 두 번 보내지 않는다.
 *
 * <p>사용자 요청 스레드는 깨우기 표시만 남기고 돌아간다. 브로커가 멈춰 보내기가 막혀도 그 대기는 이 스레드에만 걸린다.
 * 발행하는 동안 들어온 깨우기는 "한 번 더 돌기" 하나로 합친다. 커밋마다 발행을 따로 돌리면 커밋 수만큼 Outbox 조회와
 * 트랜잭션이 생기고, 합치면 그사이 쌓인 요청을 다음 한 번이 함께 보내고 함께 지운다. 이미 돌고 있으면 실행기에 넣지 않으므로
 * 발행이 막혀 있는 동안 깨우기가 몰려도 대기열은 하나를 넘지 않는다.
 *
 * <p>보내다 실패하면 이번 차례를 끝내고 남은 행은 다음 깨우기나 발행 스케줄러가 보낸다. 브로커가 멈춰 있으면 커밋마다 다시 시도해
 * 실패하지만, 발행기가 한 번에 하나만 돌아 그동안 잡는 연결은 하나이고, 그때는 결과 소비자도 받을 메시지가 없다.
 *
 * <p>발행 스케줄러와 같은 단계에서 멈춘다. 데이터소스와 Kafka 연결이 닫히기 전에 하던 발행을 끝내기 위해서다.
 */
@Slf4j
public class OutboxPublishWaker implements OutboxWakeup, SmartLifecycle {

    private final OutboxPublisher publisher;
    private final int batchSize;
    private final Duration sendTimeout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "booking-outbox-wakeup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean started;

    public OutboxPublishWaker(OutboxPublisher publisher, int batchSize, Duration sendTimeout) {
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void wake() {
        pending.set(true);
        if (running.compareAndSet(false, true)) {
            submit();
        }
    }

    private void submit() {
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            // 멈추는 중이다. 남은 행은 다음 기동의 발행 스케줄러가 보낸다
            running.set(false);
        }
    }

    private void drain() {
        try {
            while (pending.getAndSet(false)) {
                publishAll();
            }
        } catch (RuntimeException e) {
            log.warn("커밋 직후 발행이 실패했다. 남은 행은 발행 스케줄러가 보낸다", e);
        } finally {
            running.set(false);
            // 마지막 확인과 running 해제 사이에 들어온 깨우기는 아무도 돌리지 않으므로 여기서 다시 돌린다
            if (pending.get() && running.compareAndSet(false, true)) {
                submit();
            }
        }
    }

    /** 한 번에 batchSize개까지 보내므로, 꽉 채워 보냈으면 더 남았을 수 있어 다시 돈다. 덜 보냈으면 다 보냈거나 실패한 것이다. */
    private void publishAll() {
        int sent;
        do {
            sent = publisher.publish(batchSize, sendTimeout);
        } while (sent == batchSize);
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        started = false;
        executor.shutdown();
        try {
            // 하던 발행은 보내기 하나를 기다리는 동안 끝나야 한다. 넘으면 끊고, 지우지 못한 행은 다음 기동 때 다시 보낸다
            if (!executor.awaitTermination(sendTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return started;
    }
}
