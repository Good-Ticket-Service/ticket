package com.sparta.ticket.payment.infrastructure.settlement.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementProjectionCorpusTest {
    @TempDir
    Path state;

    @Test
    void shuffledFixedPoliciesMidnightRefundActionsAndDuplicatesMatchIndependentOracle() {
        var input = new ArrayList<SettlementFact>();
        var expected = new HashMap<String, long[]>();
        var membership = new HashSet<String>();
        for (int payment = 1; payment <= 200; payment++) {
            long amount = payment % 2 == 0 ? 8000 : 8100;
            String policy = payment % 2 == 0 ? "old-policy" : "new-policy";
            long seller = 1 + payment % 10;
            long schedule = 1 + payment % 20;
            var sale = new SettlementFact(
                    "sale-" + payment,
                    "v1:SALE:" + payment + ":POST",
                    SettlementFact.Kind.SALE,
                    "POST",
                    payment,
                    null,
                    seller,
                    schedule,
                    LocalDateTime.of(2026, 11, 10, 23, 59),
                    amount,
                    policy);
            input.add(sale);
            input.add(sale);
            input.add(copy(sale, "retry-sale-" + payment));
            String saleKey = seller + ":" + schedule + ":2026-11-10";
            var sales = expected.computeIfAbsent(saleKey, key -> new long[4]);
            sales[0]++;
            sales[1] += amount;
            membership.add(sale.sourceFactKey());
            if (payment % 4 == 0) {
                String action = payment % 8 == 0 ? "RECEIVABLE" : "REVERSE";
                var refund = new SettlementFact(
                        "refund-" + payment,
                        "v1:REFUND:" + payment + ":" + action,
                        SettlementFact.Kind.REFUND,
                        action,
                        payment,
                        (long) payment,
                        seller,
                        schedule,
                        LocalDateTime.of(2026, 11, 11, 0, 1),
                        amount,
                        policy);
                input.add(refund);
                input.add(copy(refund, "retry-refund-" + payment));
                var refunds = expected.computeIfAbsent(seller + ":" + schedule + ":2026-11-11", key -> new long[4]);
                refunds[2]++;
                refunds[3] += amount;
                membership.add(refund.sourceFactKey());
            }
        }
        assertThat(input).hasSize(700);
        Collections.shuffle(input, new Random(334));
        var properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "corpus-334");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, state.toString());
        try (var driver =
                new TopologyTestDriver(SettlementProjectionTopology.create("build", "input", "output"), properties)) {
            var topic = driver.createInputTopic("input", new StringSerializer(), new StringSerializer());
            var output = driver.createOutputTopic(
                    "output",
                    Serdes.String().deserializer(),
                    ProjectionJson.serde(ProjectionResult.class).deserializer());
            input.forEach(fact -> topic.pipeInput(Long.toString(fact.paymentId()), ProjectionJson.write(fact)));
            var results = output.readValuesToList();
            assertThat(results.stream().filter(r -> r.type().equals("ERROR"))).isEmpty();
            var receipts = results.stream()
                    .filter(r -> r.type().equals("TOTAL") && r.factKey() != null)
                    .map(ProjectionResult::factKey)
                    .toList();
            assertThat(receipts).hasSize(250).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(membership);
            var latest = new HashMap<String, ProjectionResult>();
            results.stream().filter(r -> r.type().equals("TOTAL")).forEach(r -> latest.put(r.key(), r));
            assertThat(latest.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((key, values) -> {
                var actual = latest.get(key).totals();
                assertThat(List.of(
                                actual.saleCount(),
                                actual.saleSellerAmount(),
                                actual.refundCount(),
                                actual.refundSellerAmount()))
                        .as(key)
                        .containsExactly(values[0], values[1], values[2], values[3]);
            });
            assertThat(latest.values().stream()
                            .mapToLong(r -> r.totals().netSellerAmount())
                            .sum())
                    .isEqualTo(1210000);
        }
    }

    private static SettlementFact copy(SettlementFact fact, String eventId) {
        return new SettlementFact(
                eventId,
                fact.sourceFactKey(),
                fact.kind(),
                fact.action(),
                fact.paymentId(),
                fact.refundId(),
                fact.sellerId(),
                fact.scheduleId(),
                fact.occurredAt(),
                fact.sellerAmount(),
                fact.policyVersion());
    }
}
