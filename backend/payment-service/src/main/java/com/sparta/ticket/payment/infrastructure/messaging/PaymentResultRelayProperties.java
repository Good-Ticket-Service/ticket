package com.sparta.ticket.payment.infrastructure.messaging;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.messaging.result-relay")
public record PaymentResultRelayProperties(
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("2s") Duration sendTimeout,
        @DefaultValue("1s") Duration maxRun,
        @DefaultValue("1s") Duration retryDelay) {
    @AssertTrue(message = "발행 예산은 양수이고 lease는 실행 예산과 단일 송신 대기보다 길어야 한다")
    public boolean isBudgetValid() {
        return lease != null
                && sendTimeout != null
                && maxRun != null
                && retryDelay != null
                && !sendTimeout.isNegative()
                && !sendTimeout.isZero()
                && !maxRun.isNegative()
                && !maxRun.isZero()
                && !retryDelay.isNegative()
                && !retryDelay.isZero()
                && lease.compareTo(maxRun.plus(sendTimeout)) > 0;
    }
}
