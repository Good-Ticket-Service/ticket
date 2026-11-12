package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.payment.SettlementJobTransactions;
import com.sparta.ticket.payment.application.service.payment.SettlementPostingProperties;
import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerConflictException;
import com.sparta.ticket.payment.domain.model.settlement.LedgerKey;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobTransport;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.FinancialReplayOutboxJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.JdbcSettlementJobQueue;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** broker는 제어 Future, 금융·격리·운영 명령은 실제 PostgreSQL TX로 장애 경계를 검증한다. */
class FinancialConsumerRecoveryIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong SELLERS = new AtomicLong(93_630_000);
    private static final AtomicLong OFFSETS = new AtomicLong();

    @Autowired
    private PaymentService paymentsService;

    @Autowired
    private PaymentTransactions paymentsTransactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private SettlementJobTransactions posting;

    @Autowired
    private FinancialConsumerRecovery recovery;

    @Autowired
    private FinancialReplayOutboxJdbc replays;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementPostingOutboxJdbc postingOutbox;

    private SettlementPaymentFlow flow;
    private SettlementPostingKafkaProperties config;
    private KafkaTemplate<String, String> kafka;
    private SimpleMeterRegistry meters;
    private SettlementPostingConsumer consumer;
    private final List<ConsumerRecord<String, String>> sources = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configure() {
        flow = new SettlementPaymentFlow(
                paymentsService, paymentsTransactions, payments, jdbc, paymentClock, settlementJobs);
        config = new SettlementPostingKafkaProperties();
        config.setTopic("sk363-recovery-fixture");
        config.setGroup("sk363-fixture-" + UUID.randomUUID());
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        meters = new SimpleMeterRegistry();
        consumer = new SettlementPostingConsumer(
                posting, mapper, meters, new SettlementPostingDeadLetter(recovery, mapper, kafka, config, meters));
    }

    private ConsumerRecord<String, String> source() {
        var payment = flow.inProgress(SELLERS.incrementAndGet(), SELLERS.get());
        var queue = new JdbcSettlementJobQueue(
                jdbc, () -> Optional.of("TEST"), new SettlementPostingProperties(SettlementJobTransport.KAFKA));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var locked = payments.lockById(payment.getId()).orElseThrow();
            var now = LocalDateTime.now(paymentClock);
            locked.approve(locked.getPgTransactionId(), now);
            locked.recordPgApproval(now, locked.getAmount());
            payments.saveAndFlush(locked);
            queue.enqueue(locked.getId(), SettlementJobReason.PAYMENT_APPROVED, now);
        });
        String payload = jdbc.queryForObject(
                "SELECT event_payload FROM p_settlement_jobs WHERE payment_id=?", String.class, payment.getId());
        var record = record(payment.getId().toString(), payload);
        sources.add(record);
        return record;
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>(config.getTopic(), 0, OFFSETS.incrementAndGet(), key, value);
    }

    private FinancialConsumerRecovery.Incident quarantine(ConsumerRecord<String, String> record) throws Exception {
        var command =
                mapper.readValue(record.value(), SettlementPostingEvent.class).command();
        return recovery.quarantine(
                new FinancialConsumerRecovery.Source(
                        "POSTING",
                        config.getGroup(),
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        SettlementFact.hash("S" + record.value())),
                "LEDGER_CONFLICT",
                command);
    }

    private String jobStatus(ConsumerRecord<String, String> record) {
        return jdbc.queryForObject(
                "SELECT status FROM p_settlement_jobs WHERE payment_id=?", String.class, Long.valueOf(record.key()));
    }

    private long journals(ConsumerRecord<String, String> record) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE payment_id=?",
                Long.class,
                Long.valueOf(record.key()));
    }

    @AfterEach
    void restoreFixture() throws Exception {
        jdbc.update(
                "DELETE FROM p_financial_replay_commands WHERE incident_id IN (SELECT incident_id FROM p_financial_consumer_incidents WHERE group_id=?)",
                config.getGroup());
        jdbc.update("DELETE FROM p_financial_consumer_incidents WHERE group_id=?", config.getGroup());
        for (var record : sources) {
            jdbc.update(
                    "UPDATE p_settlement_jobs SET status='PENDING',done_at=NULL,last_error=NULL WHERE payment_id=? AND status='BLOCKED'",
                    Long.valueOf(record.key()));
            posting.processKafka(mapper.readValue(record.value(), SettlementPostingEvent.class)
                    .command());
        }
        meters.close();
    }

    @Test
    void ackLossAfterDatabaseCommitReplaysWithoutExtraFinance() throws Exception {
        var record = source();
        assertThatThrownBy(() -> consumer.consume(record, () -> {
                    assertThat(jobStatus(record)).isEqualTo("DONE");
                    throw new IllegalStateException("ACK_LOST");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ACK_LOST");
        assertThat(journals(record)).isEqualTo(1);
        AtomicInteger ack = new AtomicInteger();
        consumer.consume(record, ack::incrementAndGet);
        assertThat(ack).hasValue(1);
        assertThat(journals(record)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_facts WHERE payment_id=?",
                        Long.class,
                        Long.valueOf(record.key())))
                .isEqualTo(1);
        verifyNoInteractions(kafka);
    }

    @Test
    void unknownBugPropagatesButKnownFinancialConflictBlocksOnlyVerifiedSource() throws Exception {
        var record = source();
        var failed = mock(SettlementJobTransactions.class);
        var controlled = new SettlementPostingConsumer(
                failed, mapper, meters, new SettlementPostingDeadLetter(recovery, mapper, kafka, config, meters));
        AtomicInteger ack = new AtomicInteger();
        when(failed.processKafka(any())).thenThrow(new IllegalStateException("CONTROLLED_PROGRAMMING_ERROR"));
        assertThatThrownBy(() -> controlled.consume(record, ack::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONTROLLED_PROGRAMMING_ERROR");
        assertThat(ack).hasValue(0);
        verifyNoInteractions(kafka);
        assertThat(jobStatus(record)).isEqualTo("PENDING");
        org.mockito.Mockito.doThrow(new LedgerConflictException(
                        new LedgerKey(LedgerSourceType.SALE, Long.parseLong(record.key()), LedgerAction.POST)))
                .when(failed)
                .processKafka(any());
        controlled.consume(record, ack::incrementAndGet);
        assertThat(ack).hasValue(1);
        assertThat(jobStatus(record)).isEqualTo("BLOCKED");
        assertThat(journals(record)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT source_job_id FROM p_financial_consumer_incidents WHERE group_id=?",
                        Long.class,
                        config.getGroup()))
                .isEqualTo(mapper.readValue(record.value(), SettlementPostingEvent.class)
                        .jobId());
    }

    @Test
    void incidentStorageFailureDoesNotSendDltOrAcknowledgeOriginal() throws Exception {
        var record = record(null, "null");
        AtomicInteger ack = new AtomicInteger();
        jdbc.execute(
                "CREATE FUNCTION sk363_incident_store_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.group_id='"
                        + config.getGroup()
                        + "' THEN RAISE EXCEPTION 'controlled incident store failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER sk363_incident_store_fault BEFORE INSERT ON p_financial_consumer_incidents FOR EACH ROW EXECUTE FUNCTION sk363_incident_store_fault()");
        try {
            assertThatThrownBy(() -> consumer.consume(record, ack::incrementAndGet))
                    .isInstanceOf(RuntimeException.class);
            assertThat(ack).hasValue(0);
            verifyNoInteractions(kafka);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_financial_consumer_incidents WHERE group_id=?",
                            Long.class,
                            config.getGroup()))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER sk363_incident_store_fault ON p_financial_consumer_incidents");
            jdbc.execute("DROP FUNCTION sk363_incident_store_fault()");
        }
        consumer.consume(record, ack::incrementAndGet);
        assertThat(ack).hasValue(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dltFailureKeepsOffsetAndDeduplicatesDurableIncidentOnRetry() throws Exception {
        var record = record(null, "{\"pgAuthToken\":\"sk363-secret-fixture\",\"broken\":");
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SECRET_MUST_NOT_LEAK")));
        AtomicInteger ack = new AtomicInteger();
        assertThatThrownBy(() -> consumer.consume(record, ack::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DLT_UNCONFIRMED");
        assertThat(ack).hasValue(0);
        var first = recovery.list(0, 100, "OPEN").stream()
                .filter(row -> config.getGroup().equals(row.get("group")))
                .findFirst()
                .orElseThrow();
        assertThat(first.get("dltConfirmedAt")).isNull();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        consumer.consume(record, ack::incrementAndGet);
        assertThat(ack).hasValue(1);
        var row = jdbc.queryForMap(
                "SELECT incident_id,deliveries,dlt_confirmed_at,source_job_id FROM p_financial_consumer_incidents WHERE group_id=?",
                config.getGroup());
        assertThat(row.get("incident_id")).isEqualTo(first.get("incidentId"));
        assertThat(row.get("deliveries")).isEqualTo(2);
        assertThat(row.get("dlt_confirmed_at")).isNotNull();
        assertThat(row.get("source_job_id")).isNull();
        var captured = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(captured.capture());
        for (var message : captured.getAllValues()) {
            assertThat(message.value().toString())
                    .doesNotContain("pgAuthToken", "sk363-secret-fixture", "SECRET_MUST_NOT_LEAK", "broken");
            assertThat(message.headers().toArray()).isEmpty();
        }
    }

    @Test
    void forgedReferencesCannotBlockJobOrResolveAnotherReplay() throws Exception {
        var original = source();
        for (String field : List.of("eventId", "paymentId", "replayCommandId")) {
            var tree = mapper.readTree(original.value());
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            if ("paymentId".equals(field)) object.put(field, Long.parseLong(original.key()) + 12345);
            else object.put(field, UUID.randomUUID().toString());
            String key = "paymentId".equals(field) ? object.get(field).asText() : original.key();
            consumer.consume(record(key, mapper.writeValueAsString(tree)), () -> {});
            assertThat(jobStatus(original)).isEqualTo("PENDING");
            assertThat(journals(original)).isZero();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_financial_consumer_incidents WHERE group_id=? AND source_job_id IS NOT NULL",
                        Long.class,
                        config.getGroup()))
                .isZero();
        consumer.consume(original, () -> {});
        assertThat(jobStatus(original)).isEqualTo("DONE");
        assertThat(journals(original)).isEqualTo(1);
    }

    @Test
    void replayOutcomeAndIncidentResolutionRollbackWithFinanceAndThenConvergeOnce() throws Exception {
        var original = source();
        var incident = quarantine(original);
        assertThat(jobStatus(original)).isEqualTo("BLOCKED");
        UUID commandId = UUID.randomUUID();
        assertThat(recovery.request(commandId, incident.id(), "REPLAY", "USER:1", "원인 수정 후 재처리")
                        .status())
                .isEqualTo("REQUESTED");
        assertThat(recovery.request(commandId, incident.id(), "REPLAY", "USER:1", "원인 수정 후 재처리")
                        .status())
                .isEqualTo("REQUESTED");
        assertThatThrownBy(() -> recovery.request(commandId, incident.id(), "REPLAY", "USER:1", "다른 입력"))
                .isInstanceOf(BusinessException.class);
        var leased = new TransactionTemplate(manager).execute(status -> replays.claim(Duration.ofSeconds(30)));
        assertThat(leased).hasSize(1);
        replays.published(leased.get(0));
        assertThat(recovery.command(commandId).get("status")).isEqualTo("PUBLISHED");
        String payload = jdbc.queryForObject(
                "SELECT payload FROM p_financial_replay_commands WHERE command_id=?", String.class, commandId);
        var replay = record(original.key(), payload);
        AtomicInteger ack = new AtomicInteger();
        jdbc.execute(
                "CREATE FUNCTION sk363_replay_completion_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.command_id='"
                        + commandId
                        + "'::uuid AND NEW.status='DONE' THEN RAISE EXCEPTION 'controlled replay completion failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER sk363_replay_completion_fault BEFORE UPDATE ON p_financial_replay_commands FOR EACH ROW EXECUTE FUNCTION sk363_replay_completion_fault()");
        try {
            assertThatThrownBy(() -> consumer.consume(replay, ack::incrementAndGet))
                    .isInstanceOf(RuntimeException.class);
            assertThat(ack).hasValue(0);
            assertThat(jobStatus(original)).isEqualTo("PENDING");
            assertThat(journals(original)).isZero();
            assertThat(recovery.command(commandId).get("status")).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM p_financial_consumer_incidents WHERE incident_id=?",
                            String.class,
                            incident.id()))
                    .isEqualTo("REPLAY_REQUESTED");
        } finally {
            jdbc.execute("DROP TRIGGER sk363_replay_completion_fault ON p_financial_replay_commands");
            jdbc.execute("DROP FUNCTION sk363_replay_completion_fault()");
        }
        consumer.consume(replay, ack::incrementAndGet);
        consumer.consume(replay, ack::incrementAndGet);
        assertThat(ack).hasValue(2);
        assertThat(journals(original)).isEqualTo(1);
        assertThat(recovery.command(commandId).get("status")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM p_financial_consumer_incidents WHERE incident_id=?",
                        String.class,
                        incident.id()))
                .isEqualTo("RESOLVED");
    }

    @Test
    void publishedButUnconsumedWorkCanRedriveWithoutChangingIdentityOrBypassingQuarantine() throws Exception {
        paymentClock.advance(Duration.ofMinutes(-20));
        var done = source();
        consumer.consume(done, () -> {});
        var recent = source();
        var overdue = source();
        var blocked = source();
        quarantine(blocked);
        jdbc.update(
                "UPDATE p_settlement_jobs SET published_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-interval '20 minutes' WHERE payment_id IN (?,?,?)",
                Long.valueOf(done.key()),
                Long.valueOf(overdue.key()),
                Long.valueOf(blocked.key()));
        jdbc.update(
                "UPDATE p_settlement_jobs SET published_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE payment_id=?",
                Long.valueOf(recent.key()));
        assertThat(postingOutbox.redriveOverdue(10, Duration.ofMinutes(10))).isEqualTo(1);
        assertThat(postingOutbox.redriveOverdue(10, Duration.ofMinutes(10))).isZero();
        var claimed = postingOutbox.claim(10, Duration.ofSeconds(30));
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).payload()).isEqualTo(overdue.value());
        assertThat(jobStatus(blocked)).isEqualTo("BLOCKED");
        consumer.consume(overdue, () -> {});
        consumer.consume(overdue, () -> {});
        assertThat(journals(overdue)).isEqualTo(1);
    }

    @Test
    void publishedReplayCanRedriveUntilActualApplyAndNeverAfterDone() throws Exception {
        var original = source();
        var incident = quarantine(original);
        UUID command = UUID.randomUUID();
        recovery.request(command, incident.id(), "REPLAY", "USER:1", "broker 보존 밖 복구 검증");
        var delivery = replays.claim(Duration.ofSeconds(30)).get(0);
        replays.published(delivery);
        jdbc.update(
                "UPDATE p_financial_replay_commands SET published_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-interval '20 minutes' WHERE command_id=?",
                command);
        assertThat(replays.redriveOverdue()).isEqualTo(1);
        var again = replays.claim(Duration.ofSeconds(30)).get(0);
        assertThat(again.payload()).isEqualTo(delivery.payload());
        replays.published(again);
        consumer.consume(record(again.key(), again.payload()), () -> {});
        assertThat(recovery.command(command).get("status")).isEqualTo("DONE");
        assertThat(replays.redriveOverdue()).isZero();
        assertThat(journals(original)).isEqualTo(1);
    }

    @Test
    void masterOnlyOperationsAuditDenialWithoutHidingUnfinishedFinance() throws Exception {
        var original = source();
        var incident = quarantine(original);
        mvc.perform(get("/api/admin/settlements/consumers/incidents")
                        .header("X-User-Id", "1")
                        .header("X-Username", "fixture")
                        .header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/settlements/consumers/incidents")
                        .header("X-User-Id", "1")
                        .header("X-Username", "fixture")
                        .header("X-User-Role", "MASTER"))
                .andExpect(status().isOk());
        UUID denied = UUID.randomUUID();
        mvc.perform(post("/api/admin/settlements/consumers/incidents/" + incident.id() + "/resolve")
                        .header("X-User-Id", "1")
                        .header("X-Username", "fixture")
                        .header("X-User-Role", "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("commandId", denied, "reason", "미완료 숨김 방지 확인"))))
                .andExpect(status().isConflict());
        assertThat(recovery.command(denied)).containsEntry("status", "DENIED").containsEntry("actor", "USER:1");
        assertThat(jobStatus(original)).isEqualTo("BLOCKED");
        assertThat(journals(original)).isZero();
    }
}
