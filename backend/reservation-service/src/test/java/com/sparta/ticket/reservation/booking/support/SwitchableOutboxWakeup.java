package com.sparta.ticket.reservation.booking.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxPublishWaker;
import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxPublisher;
import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxWakeup;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.springframework.beans.factory.DisposableBean;

/**
 * 테스트가 커밋 직후 발행을 켜고 끄는 대역. 통합 테스트는 스케줄러를 꺼서 운영의 깨우기 빈이 없으므로 이 빈이 그 자리를 맡는다.
 * 기본은 꺼짐이라 Outbox에 행이 남았는지 보는 테스트는 그대로 돈다.
 * 멈춤 모드는 실제 깨우기를 쓰되 발행기가 브로커를 기다리며 멈춘 것처럼 풀어 줄 때까지 돌아오지 않는다.
 */
public class SwitchableOutboxWakeup implements OutboxWakeup, DisposableBean {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private enum Mode {
        OFF,
        PUBLISH,
        STALLED
    }

    private final OutboxPublishWaker publishing;
    private final OutboxPublishWaker stalled;
    private volatile Mode mode = Mode.OFF;
    private volatile CountDownLatch release = new CountDownLatch(1);

    public SwitchableOutboxWakeup(OutboxPublisher publisher) {
        this.publishing = new OutboxPublishWaker(publisher, 100, SEND_TIMEOUT);
        OutboxPublisher stuck = mock(OutboxPublisher.class);
        when(stuck.publish(anyInt(), any())).thenAnswer(invocation -> {
            release.await();
            return 0;
        });
        this.stalled = new OutboxPublishWaker(stuck, 100, SEND_TIMEOUT);
    }

    public void publishOnCommit() {
        mode = Mode.PUBLISH;
    }

    public void stallPublishing() {
        mode = Mode.STALLED;
    }

    /** 멈춰 있던 발행을 풀고 끈다. 다음 테스트의 멈춤은 새 대기로 시작한다. */
    public void reset() {
        mode = Mode.OFF;
        CountDownLatch previous = release;
        release = new CountDownLatch(1);
        previous.countDown();
    }

    @Override
    public void wake() {
        switch (mode) {
            case PUBLISH -> publishing.wake();
            case STALLED -> stalled.wake();
            case OFF -> {}
        }
    }

    @Override
    public void destroy() {
        release.countDown();
        publishing.stop();
        stalled.stop();
    }
}
