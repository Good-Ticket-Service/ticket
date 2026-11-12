package com.sparta.ticket.payment.infrastructure.messaging;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("payment.settlement.posting.kafka")
public class SettlementPostingKafkaProperties {
    private boolean relayEnabled = true;
    private boolean consumerEnabled = true;

    @NotBlank
    private String topic = "settlement.posting.requested.v1";

    @NotBlank
    private String group = "settlement-posting-v1";

    @Min(1)
    @Max(6)
    private int concurrency = 2;

    @Min(1)
    @Max(1000)
    private int batchSize = 200;

    @Min(1)
    @Max(100)
    private int maxPollRecords = 20;

    private Duration lease = Duration.ofSeconds(30);
    private Duration sendTimeout = Duration.ofSeconds(2);
    private Duration maxPublishRun = Duration.ofSeconds(1);
    private Duration retryDelay = Duration.ofSeconds(1);

    @AssertTrue(message = "임대는 발행 예산+송신 대기보다 길고 모든 간격은 양수여야 한다")
    public boolean isBudgetValid() {
        return java.util.stream.Stream.of(lease, sendTimeout, maxPublishRun, retryDelay)
                        .allMatch(value -> value != null && !value.isZero() && !value.isNegative())
                && lease.compareTo(sendTimeout.plus(maxPublishRun)) > 0;
    }
}
