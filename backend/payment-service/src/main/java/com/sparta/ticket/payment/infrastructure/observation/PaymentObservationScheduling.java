package com.sparta.ticket.payment.infrastructure.observation;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 금융 @Scheduled의 기본 단일 실행 스레드와 DB 관측을 분리한다. */
@Component
@ConditionalOnProperty(name = "payment.observation.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentObservationScheduling implements DisposableBean {
    private final PaymentObservation observation;
    private final long interval;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "payment-observation");
        thread.setDaemon(true);
        return thread;
    });

    public PaymentObservationScheduling(
            PaymentObservation observation, @Value("${payment.observation.interval-ms:15000}") long interval) {
        if (interval < 1000) throw new IllegalArgumentException("관측 주기는 1초 이상이어야 한다");
        this.observation = observation;
        this.interval = interval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(observation::refresh, 0, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
