package com.sparta.ticket.payment.infrastructure.settlement.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** B 집계는 별도 Kafka 출력 sink 없이 DB에 반영한다. 영구 오류만 공통 운영 저장소/DLT로 인수한다. */
@Component
@RequiredArgsConstructor
public class ProjectionDeadLetterHandler implements ProjectionFailureHandler {
    private final FinancialConsumerRecovery recovery;
    private final ProjectionProperties config;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry meters;

    @Override
    public void reject(
            UUID build, ConsumerRecord<String, String> record, String factKey, String reason, UUID replayCommandId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("집계 격리는 실패 TX 밖에서 실행해야 한다");
        String hash = SettlementFact.hash(record.value() == null ? "N" : "S" + record.value());
        var incident = recovery.quarantineProjection(
                new FinancialConsumerRecovery.Source(
                        "PROJECTION",
                        config.applicationId(build.toString()),
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        hash),
                build,
                factKey,
                reason,
                replayCommandId);
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "version",
                    1,
                    "incidentId",
                    incident.id(),
                    "sourceTopic",
                    record.topic(),
                    "sourcePartition",
                    record.partition(),
                    "sourceOffset",
                    record.offset(),
                    "payloadHash",
                    hash,
                    "reason",
                    reason));
            kafka.send(new ProducerRecord<>(
                            config.inputTopic(build.toString()) + ".DLT",
                            record.partition(),
                            incident.id().toString(),
                            payload))
                    .get(config.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            meters.counter("settlement.projection.dlt.failures").increment();
            throw new IllegalStateException("PROJECTION_DLT_INTERRUPTED");
        } catch (Exception exception) {
            meters.counter("settlement.projection.dlt.failures").increment();
            throw new IllegalStateException("PROJECTION_DLT_UNCONFIRMED");
        }
        recovery.deadLetterConfirmed(incident.id());
        meters.counter("settlement.projection.dlt.confirmed").increment();
    }
}
