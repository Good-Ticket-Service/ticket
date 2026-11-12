package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.messaging.SettlementPostingEvent;
import com.sparta.ticket.payment.infrastructure.messaging.SettlementPostingKafkaProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

/** 장수명 broker에서 DLT/운영 명령의 실제 전송을 확인한다. 금융 오류는 명시적 제어 입력이다. */
final class SettlementKafkaRecoveryScenario {
    private SettlementKafkaRecoveryScenario() {}

    @SuppressWarnings("unchecked")
    static int run(
            ConfigurableApplicationContext context,
            JdbcTemplate jdbc,
            long paymentId,
            LocalDateTime approvedAt,
            Map<String, Object> report)
            throws Exception {
        var config = context.getBean(SettlementPostingKafkaProperties.class);
        var mapper = context.getBean(ObjectMapper.class);
        var kafka = (KafkaTemplate<String, String>) context.getBean(KafkaTemplate.class);
        var recovery = context.getBean(FinancialConsumerRecovery.class);
        String sample = jdbc.queryForObject(
                "SELECT event_payload FROM p_settlement_jobs WHERE payment_id=?", String.class, paymentId - 1);
        var unsupported = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(sample);
        unsupported.put("version", 2).put("pgAuthToken", "sk363-public-secret-marker");
        for (String poison : List.of(
                "{\"pgAuthToken\":\"sk363-public-secret-marker\",\"broken\":",
                "null",
                mapper.writeValueAsString(unsupported)))
            kafka.send(config.getTopic(), Long.toString(paymentId - 1), poison).get(5, TimeUnit.SECONDS);
        caughtUp(config.getTopic(), config.getGroup());
        int dltCount = verifyDlt(config.getTopic() + ".DLT");
        if (dltCount != 3) throw new IllegalStateException("DLT3건 확인 실패");
        var poisonRows = jdbc.queryForList(
                "SELECT incident_id,source_job_id,dlt_confirmed_at FROM p_financial_consumer_incidents WHERE group_id=?",
                config.getGroup());
        if (poisonRows.size() != 3
                || poisonRows.stream()
                        .anyMatch(row -> row.get("source_job_id") != null || row.get("dlt_confirmed_at") == null))
            throw new IllegalStateException("poison 원천 격리 검증 실패");
        for (var row : poisonRows)
            recovery.request(
                    UUID.randomUUID(),
                    (UUID) row.get("incident_id"),
                    "RESOLVE",
                    "BENCHMARK:fixture",
                    "의도한 poison 검증 종료");
        report.put("poisonMessages", 3);
        report.put("maskedDltMessagesVerified", dltCount);
        report.put("unknownPoisonDidNotBlockSource", true);
        var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("settlement-posting");
        container.stop();
        var payments = context.getBean(PaymentTransactions.class);
        String key = "sk363-approved-" + paymentId;
        if (payments.claimApproval("sk363-ready-" + paymentId, new ConfirmCommand(key, 10000))
                .isEmpty()) throw new IllegalStateException("복구 시험 승인 인수 실패");
        payments.complete(paymentId, new PgResult.Approved(key, 10000, approvedAt));
        String payload = jdbc.queryForObject(
                "SELECT event_payload FROM p_settlement_jobs WHERE payment_id=?", String.class, paymentId);
        var metadata = kafka.send(config.getTopic(), Long.toString(paymentId), payload)
                .get(5, TimeUnit.SECONDS)
                .getRecordMetadata();
        var command = mapper.readValue(payload, SettlementPostingEvent.class).command();
        // Note: 잘못된 불변 원장을 만들지 않고, 처리자가 관측한 금융 의미 충돌을 제어 입력으로 격리한다.
        var incident = recovery.quarantine(
                new FinancialConsumerRecovery.Source(
                        "POSTING",
                        config.getGroup(),
                        config.getTopic(),
                        metadata.partition(),
                        metadata.offset(),
                        SettlementFact.hash("S" + payload)),
                "LEDGER_CONFLICT",
                command);
        String blocked =
                jdbc.queryForObject("SELECT status FROM p_settlement_jobs WHERE payment_id=?", String.class, paymentId);
        if (!"BLOCKED".equals(blocked)) throw new IllegalStateException("신뢰한 작업 격리 실패");
        UUID replayId = UUID.randomUUID();
        if (!"REQUESTED"
                .equals(recovery.request(replayId, incident.id(), "REPLAY", "BENCHMARK:fixture", "제어 오류 제거 후 원천 재전달")
                        .status())) throw new IllegalStateException("재처리 인수 실패");
        container.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!"DONE".equals(recovery.command(replayId).get("status")) && System.nanoTime() < deadline)
            Thread.sleep(100);
        if (!"DONE".equals(recovery.command(replayId).get("status"))) throw new IllegalStateException("재처리 완료 시간 초과");
        caughtUp(config.getTopic(), config.getGroup());
        long journals = jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE payment_id=?", Long.class, paymentId);
        long facts = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_facts WHERE payment_id=?", Long.class, paymentId);
        long open = jdbc.queryForObject(
                "SELECT count(*) FROM p_financial_consumer_incidents WHERE group_id=? AND status<>'RESOLVED'",
                Long.class,
                config.getGroup());
        if (journals != 1 || facts != 1 || open != 0) throw new IllegalStateException("복구 후 금융/격리 수렴 실패");
        report.put("controlledFailure", "처리자 중지 후 영속 source 대조로 LedgerConflict 격리를 제어 주입. 실제 잘못된 원장은 생성하지 않음");
        report.put("recoveryAdditionalPayment", paymentId);
        report.put("replayCommandId", replayId);
        report.put("replayCommandStatus", "DONE");
        report.put("replaySourceJournals", journals);
        report.put("replaySourceFacts", facts);
        report.put("unresolvedIncidents", open);
        return 1;
    }

    private static void caughtUp(String topic, String group) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", "127.0.0.1:19063"))) {
            var selection = new HashMap<TopicPartition, OffsetSpec>();
            for (int partition = 0; partition < 6; partition++)
                selection.put(new TopicPartition(topic, partition), OffsetSpec.latest());
            var ends = admin.listOffsets(selection).all().get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                var offsets = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                if (ends.entrySet().stream()
                        .allMatch(entry -> offsets.containsKey(entry.getKey())
                                && offsets.get(entry.getKey()).offset()
                                        >= entry.getValue().offset())) return;
                Thread.sleep(100);
            }
            throw new IllegalStateException("원본 offset 수렴 시간 초과");
        }
    }

    private static int verifyDlt(String topic) {
        var properties = new HashMap<String, Object>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:19063");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int partition = 0; partition < 6; partition++) partitions.add(new TopicPartition(topic, partition));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            int count = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (count < 3 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    if (record.value().contains("pgAuthToken")
                            || record.value().contains("sk363-public-secret-marker")
                            || record.value().contains("broken")) throw new IllegalStateException("DLT 원문 마스킹 실패");
                    count++;
                }
            }
            return count;
        }
    }
}
