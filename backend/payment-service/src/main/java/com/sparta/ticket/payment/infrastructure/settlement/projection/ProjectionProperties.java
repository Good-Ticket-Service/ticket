package com.sparta.ticket.payment.infrastructure.settlement.projection;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.settlement.projection")
public class ProjectionProperties {
    private boolean enabled = true;
    private boolean autoStart = true;
    private String mode = "D";
    private int partitions = 6;
    private int batchSize = 500;
    private int maxPublishBatches = 8;
    private long maxPublishRunMillis = 1000;
    private int threads = 1;
    private Duration lease = Duration.ofSeconds(30);
    private Duration sendTimeout = Duration.ofSeconds(5);
    private Duration staleAfter = Duration.ofSeconds(10);
    private String topicPrefix = "settlement.projection";
    private String stateDirectory = System.getProperty("java.io.tmpdir") + "/ticket-settlement-streams";

    public void setMode(String value) {
        if (!java.util.List.of("D", "B", "C").contains(value))
            throw new IllegalArgumentException("집계 엔진은 D, B, C 중 하나다");
        mode = value;
    }

    public void setBatchSize(int value) {
        if (value < 1 || value > 1000) throw new IllegalArgumentException("집계 묶음은 1~1000건이다");
        batchSize = value;
    }

    public void setThreads(int value) {
        if (value < 1 || value > 16) throw new IllegalArgumentException("집계 소비 스레드는 1~16개다");
        threads = value;
    }

    public void setPartitions(int value) {
        if (value < 1 || value > 24) throw new IllegalArgumentException("집계 파티션은 1~24개다");
        partitions = value;
    }

    public void setMaxPublishBatches(int value) {
        if (value < 1 || value > 16) throw new IllegalArgumentException("발행 batch 반복 상한은 1~16이다");
        maxPublishBatches = value;
    }

    public void setMaxPublishRunMillis(long value) {
        if (value < 1 || value > 5000) throw new IllegalArgumentException("발행 실행 예산은 1~5000ms다");
        maxPublishRunMillis = value;
    }

    public String inputTopic(String buildId) {
        return topicPrefix + ".input." + buildId;
    }

    public String outputTopic() {
        return topicPrefix + ".results.v1";
    }

    public String applicationId(String buildId) {
        return topicPrefix + ".processor." + buildId;
    }
}
