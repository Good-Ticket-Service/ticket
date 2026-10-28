package com.sparta.ticket.payment.application.service.payment;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.recovery")
public record PaymentRecoveryProperties(
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("10") @Min(1) long checkIntervalSeconds,
        @DefaultValue("60") @Min(1) long notFoundGraceSeconds,
        @DefaultValue("60") @Min(1) long resultDeadlineSeconds,
        @DefaultValue("15") @Min(1) long maxRunSeconds) {
    public Duration interval() {
        return Duration.ofSeconds(checkIntervalSeconds);
    }

    public Duration grace() {
        return Duration.ofSeconds(notFoundGraceSeconds);
    }

    public Duration deadline() {
        return Duration.ofSeconds(resultDeadlineSeconds);
    }
}
