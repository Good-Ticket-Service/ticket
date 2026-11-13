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
    /**
     * claim 커밋 직후 acknowledge한다(#417). 끄면 PG 결과 저장 뒤 acknowledge하는 이전 동작으로 돌아간다.
     * 같은 조건 전후 비교와 운영 중 되돌리기에 쓴다.
     */
    private boolean acknowledgeOnClaim = true;
    /**
     * 원본 Topic에서 PG별 자리나 PG 허가를 얻지 못한 레코드를 PG별 retry Topic으로 넘긴다(#417 B).
     * 끄면 원본 Topic 안에서 재시도로 기다리는 이전 동작이다.
     */
    private boolean forwardOnUnavailable = true;
    /** 원본 레인이 넘기기 전에 PG별 자리를 기다리는 시간. 순간 경합까지 넘기지 않도록 짧게 둔다. */
    private Duration slotWait = Duration.ofMillis(100);

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
                || maxMessageBytes > 65536
                || slotWait == null
                || slotWait.isNegative()
                || slotWait.toMillis() > 1000) throw new IllegalArgumentException("승인 소비 자원 설정이 허용 범위를 벗어났다");
    }
}
