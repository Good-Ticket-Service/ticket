package com.sparta.ticket.payment.application.service.payment;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReconcileRetryDelay {
    private final PaymentReconcileProperties properties;
    private final DoubleSupplier random;

    @Autowired
    public ReconcileRetryDelay(PaymentReconcileProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    ReconcileRetryDelay(PaymentReconcileProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
    }

    public Duration afterUnknown(int retryCount) {
        long base = properties.retryInitialSeconds();
        // 상한에 도달하면 끝내므로 장기 불명·재시작 후에도 overflow가 없다.
        for (int i = 1; i < retryCount && base < properties.retryMaxSeconds(); i++) {
            base = Math.min(base * 2, properties.retryMaxSeconds());
        }
        double factor = 1 + (random.getAsDouble() * 2 - 1) * properties.retryJitterRatio();
        long millis = (long) (base * 1000 * factor);
        return Duration.ofMillis(Math.max(1000, Math.min(properties.retryMaxSeconds() * 1000, millis)));
    }
}
