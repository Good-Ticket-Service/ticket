package com.sparta.ticket.payment.application.service.payment;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("payment.messaging.confirm")
public class MessageApprovalProperties {
    private boolean enabled;
    private int concurrency = 3;
    private int maxPollRecords = 16;
    private int workers = 16;
    private int perProvider = 8;
    private int maxInFlight = 48;
    private Duration retryDelay = Duration.ofMillis(200);
    private int maxInfrastructureRetries = 4;
    private int maxMessageBytes = 8192;

    public Duration readyGrace() {
        return enabled ? Duration.ofMinutes(6) : Duration.ZERO;
    }

    public void validate() {
        if (concurrency < 1
                || concurrency > 12
                || maxPollRecords < 1
                || maxPollRecords > 32
                || workers < 2
                || workers > 1000
                || perProvider < 1
                || perProvider > workers / 2
                || maxInFlight < concurrency * maxPollRecords
                || maxInFlight > 384
                || retryDelay == null
                || retryDelay.toMillis() < 10
                || retryDelay.toMillis() > 2000
                || maxInfrastructureRetries < 1
                || maxInfrastructureRetries > 10
                || maxMessageBytes < 1024
                || maxMessageBytes > 65536) throw new IllegalArgumentException("승인 소비 자원 설정이 허용 범위를 벗어났다");
    }
}
