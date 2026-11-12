package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ProjectionConsumerRecoveryTest {
    private record Services(
            FinancialConsumerRecovery recovery, ProjectionConsumerJdbc consumer, FinancialReplayOutboxJdbc outbox) {}

    private static Services services(ProjectionConsumerBatchTest.Fixture f) {
        var manager = new DataSourceTransactionManager(f.jdbc().getDataSource());
        var recovery = ProjectionKafkaIntegrationTest.proxy(new FinancialConsumerRecoveryJdbc(f.jdbc()), manager);
        var sink = ProjectionKafkaIntegrationTest.proxy(new ProjectionSinkJdbc(f.jdbc()), manager);
        return new Services(
                recovery,
                ProjectionKafkaIntegrationTest.proxy(new ProjectionConsumerJdbc(f.jdbc(), sink, recovery), manager),
                ProjectionKafkaIntegrationTest.proxy(new FinancialReplayOutboxJdbc(f.jdbc()), manager));
    }

    private static FinancialConsumerRecovery.Incident incident(
            Services services, ProjectionConsumerBatchTest.Fixture f, SettlementFact fact) {
        return services.recovery()
                .quarantineProjection(
                        new FinancialConsumerRecovery.Source(
                                "PROJECTION",
                                "fixture-" + f.build(),
                                "fixture.input." + f.build(),
                                0,
                                1,
                                SettlementFact.hash(ProjectionJson.write(fact))),
                        f.build(),
                        fact.sourceFactKey(),
                        "BUSINESS_CONFLICT",
                        null);
    }

    private static SettlementFact replay(ProjectionConsumerBatchTest.Fixture f, UUID commandId) {
        String payload = f.jdbc()
                .queryForObject(
                        "SELECT payload FROM p_financial_replay_commands WHERE command_id=?", String.class, commandId);
        var json = (ObjectNode) ProjectionJson.read(payload, JsonNode.class);
        assertThat(json.remove("replayCommandId").asText()).isEqualTo(commandId.toString());
        return ProjectionJson.read(json.toString(), SettlementFact.class);
    }

    @Test
    void legacySeenWithoutAppliedUsesNewTransportIdentityAndKeepsOldReceipt() throws Exception {
        ProjectionConsumerBatchTest.withFixture(f -> {
            var services = services(f);
            var sale = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.SALE)
                    .findFirst()
                    .orElseThrow();
            f.jdbc()
                    .update(
                            "INSERT INTO p_settlement_projection_event_ids(build_id,event_id,meaning_hash) VALUES(?,?,?)",
                            f.build(),
                            sale.eventId(),
                            sale.meaningHash());
            services.consumer().applyStrict(f.build(), List.of(sale));
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isZero();
            var incident = incident(services, f, sale);
            UUID commandId = UUID.randomUUID();
            assertThat(services.recovery()
                            .request(commandId, incident.id(), "REPLAY", "USER:1", "기존 seen과 실제 적용 구분")
                            .status())
                    .isEqualTo("REQUESTED");
            var fact = replay(f, commandId);
            assertThat(fact.eventId()).isEqualTo(commandId.toString());
            assertThat(fact.meaningHash()).isEqualTo(sale.meaningHash());
            services.consumer().applyReplay(f.build(), fact, commandId);
            services.consumer().applyReplay(f.build(), fact, commandId);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_event_ids", Long.class))
                    .isEqualTo(2);
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(1);
            assertThat(services.recovery().command(commandId).get("status")).isEqualTo("DONE");
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT status FROM p_financial_consumer_incidents WHERE incident_id=?",
                                    String.class,
                                    incident.id()))
                    .isEqualTo("RESOLVED");
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT count(*) FROM p_settlement_projection_errors WHERE NOT resolved",
                                    Long.class))
                    .isZero();
            assertThat(f.jdbc()
                            .queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class))
                    .isFalse();
        });
    }

    @Test
    void pendingRefundReplayCompletesOnlyWhenSaleActuallyAppliesItsContribution() throws Exception {
        ProjectionConsumerBatchTest.withFixture(f -> {
            var services = services(f);
            var refund = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.REFUND)
                    .findFirst()
                    .orElseThrow();
            var sale = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.SALE && fact.paymentId() == refund.paymentId())
                    .findFirst()
                    .orElseThrow();
            var incident = incident(services, f, refund);
            UUID commandId = UUID.randomUUID();
            services.recovery().request(commandId, incident.id(), "REPLAY", "USER:1", "환불 선행 재처리");
            services.consumer().applyReplay(f.build(), replay(f, commandId), commandId);
            var deliveries = services.outbox().claim(Duration.ofSeconds(30));
            assertThat(deliveries).hasSize(1);
            services.outbox().published(deliveries.get(0));
            assertThat(services.recovery().command(commandId).get("status")).isEqualTo("PUBLISHED");
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isZero();
            services.consumer().applyStrict(f.build(), List.of(sale));
            assertThat(services.recovery().command(commandId).get("status")).isEqualTo("DONE");
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(2);
            assertThat(f.jdbc()
                            .queryForObject(
                                    "SELECT sum(sale_amount-refund_amount) FROM p_settlement_projection_totals",
                                    Long.class))
                    .isZero();
        });
    }

    @Test
    void replayCompletionFailureRollsBackAllProjectionWritesAndCanRetry() throws Exception {
        ProjectionConsumerBatchTest.withFixture(f -> {
            var services = services(f);
            var sale = f.facts().stream()
                    .filter(fact -> fact.kind() == SettlementFact.Kind.SALE)
                    .findFirst()
                    .orElseThrow();
            var incident = incident(services, f, sale);
            UUID commandId = UUID.randomUUID();
            services.recovery().request(commandId, incident.id(), "REPLAY", "USER:1", "완료 기록 원자성 확인");
            var fact = replay(f, commandId);
            f.jdbc()
                    .execute(
                            "CREATE FUNCTION fail_projection_replay_done() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='DONE' THEN RAISE EXCEPTION 'controlled projection replay failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            f.jdbc()
                    .execute(
                            "CREATE TRIGGER fail_projection_replay_done BEFORE UPDATE ON p_financial_replay_commands FOR EACH ROW EXECUTE FUNCTION fail_projection_replay_done()");
            try {
                assertThatThrownBy(() -> services.consumer().applyReplay(f.build(), fact, commandId))
                        .isInstanceOf(RuntimeException.class);
                for (String table : List.of("event_ids", "payment_states", "applied", "totals"))
                    assertThat(f.jdbc()
                                    .queryForObject(
                                            "SELECT count(*) FROM p_settlement_projection_" + table, Long.class))
                            .isZero();
                assertThat(services.recovery().command(commandId).get("status")).isEqualTo("REQUESTED");
            } finally {
                f.jdbc().execute("DROP TRIGGER fail_projection_replay_done ON p_financial_replay_commands");
            }
            services.consumer().applyReplay(f.build(), fact, commandId);
            assertThat(services.recovery().command(commandId).get("status")).isEqualTo("DONE");
            assertThat(f.jdbc().queryForObject("SELECT count(*) FROM p_settlement_projection_applied", Long.class))
                    .isEqualTo(1);
        });
    }
}
