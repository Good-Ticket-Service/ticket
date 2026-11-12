package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.SettlementPostingCommand;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** incident 커밋→마스킹 DLT broker ACK→확인 커밋이 모두 끝나야 호출자가 원본 ACK를 보낸다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.posting.kafka.enabled", havingValue = "true")
public class SettlementPostingDeadLetter {
    private final FinancialConsumerRecovery recovery;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final SettlementPostingKafkaProperties config;
    private final MeterRegistry meters;

    public void recover(ConsumerRecord<String, String> record, String reason, SettlementPostingCommand trusted) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("금융 실패 TX 밖에서 격리해야 한다");
        String hash = SettlementFact.hash(record.value() == null ? "N" : "S" + record.value());
        var incident = recovery.quarantine(
                new FinancialConsumerRecovery.Source(
                        "POSTING", config.getGroup(), record.topic(), record.partition(), record.offset(), hash),
                reason,
                trusted);
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
                            config.getTopic() + ".DLT",
                            record.partition(),
                            incident.id().toString(),
                            payload))
                    .get(config.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            meters.counter("settlement.posting.dlt.failures").increment();
            throw new IllegalStateException("DLT_INTERRUPTED");
        } catch (Exception exception) {
            meters.counter("settlement.posting.dlt.failures").increment();
            throw new IllegalStateException("DLT_UNCONFIRMED");
        }
        recovery.deadLetterConfirmed(incident.id());
        meters.counter("settlement.posting.dlt.confirmed").increment();
    }
}
