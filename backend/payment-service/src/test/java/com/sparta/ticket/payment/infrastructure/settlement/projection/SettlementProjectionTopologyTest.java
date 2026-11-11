package com.sparta.ticket.payment.infrastructure.settlement.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementProjectionTopologyTest {
    @TempDir
    java.nio.file.Path state;

    @Test
    void refundBeforeSaleAndDifferentEventDuplicatesHaveOneContribution() {
        try (var driver = driver()) {
            var input = driver.createInputTopic("input", new StringSerializer(), new StringSerializer());
            var output = driver.createOutputTopic(
                    "output",
                    Serdes.String().deserializer(),
                    ProjectionJson.serde(ProjectionResult.class).deserializer());
            var refund = fact("refund", SettlementFact.Kind.REFUND, "RECEIVABLE", 8000);
            var sale = fact("sale", SettlementFact.Kind.SALE, "POST", 8000);
            input.pipeInput("1", ProjectionJson.write(refund));
            List<ProjectionResult> pending = output.readValuesToList();
            assertThat(pending).singleElement().satisfies(result -> {
                assertThat(result.type()).isEqualTo("STATE");
                assertThat(result.amount()).isEqualTo(1);
            });
            input.pipeInput("1", ProjectionJson.write(sale));
            input.pipeInput("1", ProjectionJson.write(sale));
            input.pipeInput("1", ProjectionJson.write(fact("sale-duplicate", SettlementFact.Kind.SALE, "POST", 8000)));
            input.pipeInput(
                    "1",
                    ProjectionJson.write(fact("refund-duplicate", SettlementFact.Kind.REFUND, "RECEIVABLE", 8000)));
            List<ProjectionResult> results = output.readValuesToList();
            assertThat(results.stream().filter(r -> r.type().equals("STATE")))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.amount()).isZero();
                        assertThat(r.revision()).isEqualTo(2);
                    });
            assertThat(results.stream().filter(r -> r.type().equals("TOTAL") && r.factKey() != null))
                    .hasSize(2);
            var totals = results.stream().filter(r -> r.type().equals("TOTAL")).toList();
            assertThat(totals).hasSize(2);
            assertThat(totals.stream()
                            .mapToLong(r -> r.totals().netSellerAmount())
                            .sum())
                    .isZero();
            assertThat(totals)
                    .anySatisfy(r -> {
                        assertThat(r.key()).endsWith("2026-11-10");
                        assertThat(r.totals().netSellerAmount()).isEqualTo(8000);
                    })
                    .anySatisfy(r -> {
                        assertThat(r.key()).endsWith("2026-11-11");
                        assertThat(r.totals().netSellerAmount()).isEqualTo(-8000);
                    });
        }
    }

    @Test
    void poisonAndIdentityConflictAreVisibleWithoutStoppingTheNextPayment() {
        try (var driver = driver()) {
            var input = driver.createInputTopic("input", new StringSerializer(), new StringSerializer());
            var output = driver.createOutputTopic(
                    "output",
                    Serdes.String().deserializer(),
                    ProjectionJson.serde(ProjectionResult.class).deserializer());
            input.pipeInput("bad", "{");
            input.pipeInput("null-json", "null");
            input.pipeInput("tombstone", (String) null);
            input.pipeInput("1", ProjectionJson.write(fact("sale", SettlementFact.Kind.SALE, "POST", 8000)));
            input.pipeInput("1", ProjectionJson.write(fact("sale", SettlementFact.Kind.SALE, "POST", 7000)));
            input.pipeInput("1", ProjectionJson.write(fact("other-id", SettlementFact.Kind.SALE, "POST", 7000)));
            var results = output.readValuesToList();
            assertThat(results.stream().filter(r -> r.type().equals("STATE"))).isEmpty();
            assertThat(results.stream().filter(r -> r.type().equals("ERROR")).map(ProjectionResult::error))
                    .containsExactlyInAnyOrder(
                            "MALFORMED", "MALFORMED", "MALFORMED", "EVENT_ID_CONFLICT", "BUSINESS_CONFLICT");
            assertThat(results.stream().filter(r -> r.type().equals("TOTAL")))
                    .singleElement()
                    .satisfies(r -> assertThat(r.totals().netSellerAmount()).isEqualTo(8000));
        }
    }

    private TopologyTestDriver driver() {
        var properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "projection-test-" + UUID.randomUUID());
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, state.toString());
        return new TopologyTestDriver(SettlementProjectionTopology.create("build", "input", "output"), properties);
    }

    private static SettlementFact fact(String eventId, SettlementFact.Kind kind, String action, long amount) {
        boolean sale = kind == SettlementFact.Kind.SALE;
        return new SettlementFact(
                eventId,
                SettlementFact.key(kind, sale ? 1 : 2, action),
                kind,
                action,
                1,
                sale ? null : 2L,
                3,
                4,
                LocalDateTime.of(2026, 11, sale ? 10 : 11, 12, 0),
                amount,
                "v1");
    }
}
