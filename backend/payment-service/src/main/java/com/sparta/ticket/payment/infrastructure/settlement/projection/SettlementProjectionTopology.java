package com.sparta.ticket.payment.infrastructure.settlement.projection;

import com.sparta.ticket.payment.domain.model.settlement.projection.PaymentProjectionState;
import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;

/** 전송 중복·업무 중복을 따로 검증하고 완료된 판매/환불만 집계한다. */
public final class SettlementProjectionTopology {
    private SettlementProjectionTopology() {}

    public record Envelope(SettlementFact fact, String error, String identity) {}

    public record PaymentState(PaymentProjectionState state, long revision) {}

    public record AggregateState(SettlementTotals totals, long revision) {}

    public static Topology create(String buildId, String inputTopic, String outputTopic) {
        return create(buildId, inputTopic, outputTopic, ProjectionCheckpoint.NONE);
    }

    public static Topology create(
            String buildId, String inputTopic, String outputTopic, ProjectionCheckpoint checkpoint) {
        StreamsBuilder builder = new StreamsBuilder();
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("event-identities"), Serdes.String(), Serdes.String()));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("payment-facts"),
                Serdes.String(),
                ProjectionJson.serde(PaymentState.class)));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("seller-totals"),
                Serdes.String(),
                ProjectionJson.serde(AggregateState.class)));

        var parsed = builder.stream(inputTopic, Consumed.with(Serdes.String(), Serdes.String()))
                .map((key, value) -> {
                    try {
                        SettlementFact fact = ProjectionJson.read(value, SettlementFact.class);
                        return KeyValue.pair(fact.eventId(), new Envelope(fact, null, fact.eventId()));
                    } catch (IllegalArgumentException exception) {
                        String identity = SettlementFact.hash(String.valueOf(key) + "|" + value);
                        return KeyValue.pair(identity, new Envelope(null, "MALFORMED", identity));
                    }
                });
        var checked = parsed.repartition(Repartitioned.with(Serdes.String(), ProjectionJson.serde(Envelope.class))
                        .withName("by-event-id"))
                .transform(EventIdentity::new, "event-identities");
        var accepted = checked.selectKey((key, value) -> value.fact() == null
                        ? value.identity()
                        : Long.toString(value.fact().paymentId()))
                .repartition(Repartitioned.with(Serdes.String(), ProjectionJson.serde(Envelope.class))
                        .withName("by-payment-id"))
                .flatTransform(() -> new PaymentFacts(buildId), "payment-facts");

        accepted.filter((key, value) -> !"CONTRIBUTION".equals(value.type()))
                .to(outputTopic, Produced.with(Serdes.String(), ProjectionJson.serde(ProjectionResult.class)));
        accepted.filter((key, value) -> "CONTRIBUTION".equals(value.type()))
                .selectKey((key, value) -> value.key())
                .repartition(Repartitioned.with(Serdes.String(), ProjectionJson.serde(ProjectionResult.class))
                        .withName("by-seller-schedule-day"))
                .transform(() -> new Aggregate(buildId, checkpoint), "seller-totals")
                .to(outputTopic, Produced.with(Serdes.String(), ProjectionJson.serde(ProjectionResult.class)));
        return builder.build();
    }

    private static final class EventIdentity implements Transformer<String, Envelope, KeyValue<String, Envelope>> {
        private KeyValueStore<String, String> identities;

        @Override
        @SuppressWarnings("unchecked")
        public void init(ProcessorContext context) {
            identities = (KeyValueStore<String, String>) context.getStateStore("event-identities");
        }

        @Override
        public KeyValue<String, Envelope> transform(String key, Envelope value) {
            if (value.error() != null) return KeyValue.pair(key, value);
            String hash = value.fact().meaningHash();
            String previous = identities.get(key);
            if (previous != null)
                return previous.equals(hash)
                        ? null
                        : KeyValue.pair(key, new Envelope(value.fact(), "EVENT_ID_CONFLICT", key));
            identities.put(key, hash);
            return KeyValue.pair(key, value);
        }

        @Override
        public void close() {}
    }

    private static final class PaymentFacts
            implements Transformer<String, Envelope, Iterable<KeyValue<String, ProjectionResult>>> {
        private final String buildId;
        private KeyValueStore<String, PaymentState> payments;

        private PaymentFacts(String buildId) {
            this.buildId = buildId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void init(ProcessorContext context) {
            payments = (KeyValueStore<String, PaymentState>) context.getStateStore("payment-facts");
        }

        @Override
        public Iterable<KeyValue<String, ProjectionResult>> transform(String key, Envelope value) {
            if (value.error() != null)
                return List.of(KeyValue.pair(
                        key,
                        ProjectionResult.failure(
                                buildId,
                                value.identity(),
                                value.error(),
                                value.fact() == null ? 0 : value.fact().sellerAmount())));
            PaymentState old = payments.get(key);
            PaymentProjectionState current = old == null ? PaymentProjectionState.empty() : old.state();
            var decision = current.accept(value.fact());
            if (decision.error() != null)
                return List.of(KeyValue.pair(
                        key,
                        ProjectionResult.failure(
                                buildId,
                                value.fact().sourceFactKey(),
                                decision.error(),
                                value.fact().sellerAmount())));
            if (decision.state().equals(current)) return List.of();
            long revision = old == null ? 1 : Math.addExact(old.revision(), 1);
            payments.put(key, new PaymentState(decision.state(), revision));
            List<KeyValue<String, ProjectionResult>> output = new ArrayList<>();
            // Note: 업무 상태는 changelog에 보존한다. DB에는 보류 여부가 변할 때만 전달한다.
            if (current.pending() != decision.state().pending())
                output.add(KeyValue.pair(
                        key,
                        new ProjectionResult(
                                buildId,
                                "STATE",
                                key,
                                revision,
                                null,
                                null,
                                null,
                                null,
                                decision.state().pending() ? 1 : 0)));
            for (SettlementFact contribution : decision.contributions()) {
                var delta = SettlementTotals.empty().add(contribution);
                output.add(KeyValue.pair(
                        key,
                        new ProjectionResult(
                                buildId,
                                "CONTRIBUTION",
                                contribution.aggregateKey(),
                                0,
                                delta,
                                contribution.sourceFactKey(),
                                contribution.meaningHash(),
                                null,
                                contribution.sellerAmount())));
            }
            return output;
        }

        @Override
        public void close() {}
    }

    private static final class Aggregate
            implements Transformer<String, ProjectionResult, KeyValue<String, ProjectionResult>> {
        private final String buildId;
        private final ProjectionCheckpoint checkpoint;
        private KeyValueStore<String, AggregateState> totals;

        private Aggregate(String buildId, ProjectionCheckpoint checkpoint) {
            this.buildId = buildId;
            this.checkpoint = checkpoint;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void init(ProcessorContext context) {
            totals = (KeyValueStore<String, AggregateState>) context.getStateStore("seller-totals");
        }

        @Override
        public KeyValue<String, ProjectionResult> transform(String key, ProjectionResult value) {
            AggregateState old = totals.get(key);
            SettlementTotals before = old == null ? SettlementTotals.empty() : old.totals();
            SettlementTotals delta = value.totals();
            var next = new SettlementTotals(
                    Math.addExact(before.saleCount(), delta.saleCount()),
                    Math.addExact(before.saleSellerAmount(), delta.saleSellerAmount()),
                    Math.addExact(before.refundCount(), delta.refundCount()),
                    Math.addExact(before.refundSellerAmount(), delta.refundSellerAmount()));
            long revision = old == null ? 1 : Math.addExact(old.revision(), 1);
            totals.put(key, new AggregateState(next, revision));
            checkpoint.hit(ProjectionCheckpoint.Stage.C_BEFORE_OUTPUT, key);
            // Note: 적용 확인은 금액과 같은 출력·DB TX에 둔다. 별도 분기에서 먼저 완료시키지 않는다.
            return KeyValue.pair(
                    key,
                    new ProjectionResult(
                            buildId,
                            "TOTAL",
                            key,
                            revision,
                            next,
                            value.factKey(),
                            value.meaningHash(),
                            null,
                            value.amount()));
        }

        @Override
        public void close() {}
    }
}
