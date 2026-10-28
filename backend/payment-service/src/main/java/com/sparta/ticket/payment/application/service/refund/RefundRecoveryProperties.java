package com.sparta.ticket.payment.application.service.refund;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.recovery.refund")
public record RefundRecoveryProperties(
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("30") @Min(1) long leaseSeconds,
        @DefaultValue("300") @Min(1) long backoffMaxSeconds,
        @DefaultValue("60") @Min(1) long slowAfterMinutes,
        @DefaultValue("15") @Min(1) long maxRunSeconds,
        @DefaultValue("600") @Min(1) long slowInitialSeconds,
        @DefaultValue("1800") @Min(1) long slowMaxSeconds,
        @DefaultValue("0.2") double jitterRatio,
        @DefaultValue("24") @Min(1) long maxCancelAgeHours) {
    @ConstructorBinding
    public RefundRecoveryProperties {
        if (batchSize < 1
                || leaseSeconds < 1
                || backoffMaxSeconds < 1
                || slowAfterMinutes < 1
                || maxRunSeconds < 1
                || slowInitialSeconds < leaseSeconds
                || slowMaxSeconds < slowInitialSeconds
                || !Double.isFinite(jitterRatio)
                || jitterRatio < 0
                || jitterRatio >= 1
                || maxCancelAgeHours < 1
                || maxCancelAgeHours > 24) throw new IllegalArgumentException("잘못된 환불 Recovery 설정");
    }

    public RefundRecoveryProperties(
            int batchSize, long leaseSeconds, long backoffMaxSeconds, long slowAfterMinutes, long maxRunSeconds) {
        this(batchSize, leaseSeconds, backoffMaxSeconds, slowAfterMinutes, maxRunSeconds, 600, 1800, 0.2, 24);
    }

    public Duration slowAfter() {
        return Duration.ofMinutes(slowAfterMinutes);
    }

    public Duration maxCancelAge() {
        return Duration.ofHours(maxCancelAgeHours);
    }

    public long claimSeconds(int attempts) {
        return Math.max(leaseSeconds, exponential(5, backoffMaxSeconds, attempts));
    }

    public long slowSeconds(int attempts, double random) {
        if (!Double.isFinite(random) || random < 0 || random > 1) throw new IllegalArgumentException("난수 범위 오류");
        long base = exponential(slowInitialSeconds, slowMaxSeconds, attempts);
        return Math.max(
                leaseSeconds, Math.min(slowMaxSeconds, (long) (base * (1 - jitterRatio + 2 * jitterRatio * random))));
    }

    private static long exponential(long initial, long maximum, int attempts) {
        long delay = Math.min(initial, maximum);
        for (int i = 0; i < attempts && delay < maximum; i++) delay = delay > maximum / 2 ? maximum : delay * 2;
        return delay;
    }
}
