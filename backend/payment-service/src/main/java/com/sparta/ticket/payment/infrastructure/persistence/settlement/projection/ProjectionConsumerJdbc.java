package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.payment.application.port.settlement.ProjectionBatchApplier;
import com.sparta.ticket.payment.domain.model.settlement.projection.PaymentProjectionState;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 일반 Consumer 비교군. 묶음 DB 왕복과 정렬된 행 잠금으로 동일 업무 검증·원자 커밋을 보존한다. */
@Repository
@RequiredArgsConstructor
public class ProjectionConsumerJdbc implements ProjectionBatchApplier {
    private final JdbcTemplate jdbc;
    private final ProjectionSinkJdbc sink;

    private record State(PaymentProjectionState value, long revision) {}

    @Override
    @Transactional
    public void apply(UUID build, List<SettlementFact> facts) {
        if (facts.isEmpty()) return;
        var builds = jdbc.queryForList("SELECT status FROM p_settlement_projection_builds WHERE id=? FOR SHARE", build);
        if (builds.isEmpty()
                || List.of("RETIRED", "FAILED").contains(builds.get(0).get("status"))) return;
        var keys = facts.stream().map(SettlementFact::sourceFactKey).distinct().toList();
        var membership = new HashMap<String, String>();
        var membershipArgs = new ArrayList<Object>();
        membershipArgs.add(build);
        membershipArgs.addAll(keys);
        jdbc.query(
                "SELECT f.fact_key,f.meaning_hash FROM p_settlement_projection_deliveries d "
                        + "JOIN p_settlement_projection_facts f USING(fact_key) WHERE d.build_id=? AND d.fact_key IN ("
                        + placeholders(keys.size()) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> membership.put(rs.getString(1), rs.getString(2)),
                membershipArgs.toArray());
        var valid = new ArrayList<SettlementFact>();
        var firstEvents = new LinkedHashMap<String, SettlementFact>();
        for (var fact : facts) {
            if (!fact.meaningHash().equals(membership.get(fact.sourceFactKey()))) {
                sink.error(build, fact.sourceFactKey(), "SOURCE_MEMBERSHIP_CONFLICT", fact.sellerAmount());
                continue;
            }
            valid.add(fact);
            firstEvents.putIfAbsent(fact.eventId(), fact);
        }
        if (valid.isEmpty()) return;
        var eventHashes = new LinkedHashMap<String, String>();
        firstEvents.forEach((key, value) -> eventHashes.put(key, value.meaningHash()));
        var newEvents =
                new HashSet<>(insertUnique(build, eventHashes, "p_settlement_projection_event_ids", "event_id"));
        var persistedEvents =
                hashes(build, new ArrayList<>(eventHashes.keySet()), "p_settlement_projection_event_ids", "event_id");
        var payments = firstEvents.values().stream()
                .filter(fact -> newEvents.contains(fact.eventId()))
                .map(SettlementFact::paymentId)
                .distinct()
                .sorted()
                .toList();
        var states = lockStates(build, payments);
        var changed = new HashSet<Long>();
        var receipts = new LinkedHashMap<String, String>();
        var deltas = new HashMap<String, SettlementTotals>();
        for (var fact : valid) {
            if (!fact.meaningHash().equals(persistedEvents.get(fact.eventId()))) {
                sink.error(build, fact.sourceFactKey(), "EVENT_ID_CONFLICT", fact.sellerAmount());
                continue;
            }
            if (!newEvents.remove(fact.eventId())) continue;
            State old = states.get(fact.paymentId());
            if (old == null) throw new IllegalStateException("결제별 집계 잠금 상태가 없다");
            var decision = old.value().accept(fact);
            if (decision.error() != null) {
                sink.error(build, fact.sourceFactKey(), decision.error(), fact.sellerAmount());
                continue;
            }
            states.put(fact.paymentId(), new State(decision.state(), Math.addExact(old.revision(), 1)));
            changed.add(fact.paymentId());
            for (var contribution : decision.contributions()) {
                if (receipts.putIfAbsent(contribution.sourceFactKey(), contribution.meaningHash()) != null)
                    throw new IllegalStateException("같은 묶음에 중복 기여가 생성됐다");
                deltas.compute(
                        contribution.aggregateKey(),
                        (key, totals) -> (totals == null ? SettlementTotals.empty() : totals).add(contribution));
            }
        }
        if (insertUnique(build, receipts, "p_settlement_projection_applied", "fact_key")
                        .size()
                != receipts.size()) throw new IllegalStateException("업무 상태와 적용 기록이 불일치한다");
        var updates = new ArrayList<Object[]>();
        for (Long payment : changed.stream().sorted().toList()) {
            State state = states.get(payment);
            String payload = ProjectionJson.write(state.value());
            updates.add(new Object[] {
                payload, state.value().pending(), state.revision(), SettlementFact.hash(payload), build, payment
            });
        }
        if (!updates.isEmpty())
            jdbc.batchUpdate(
                    "UPDATE p_settlement_projection_payment_states SET payload=?,pending=?,revision=?,meaning_hash=? "
                            + "WHERE build_id=? AND payment_id=?",
                    updates);
        mergeTotals(build, deltas);
        sink.markApplied(build, receipts.keySet());
    }

    /** INSERT RETURNING으로 실제 승자만 처리한다. 사전 조회만으로 중복 여부를 추정하지 않는다. */
    private List<String> insertUnique(UUID build, Map<String, String> entries, String table, String identity) {
        if (entries.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            args.add(build);
            args.add(entry.getKey());
            args.add(entry.getValue());
        });
        // Note: table/identity는 이 클래스의 고정 상수 호출만 받으며 사건 값은 모두 bind한다.
        return jdbc.query(
                "INSERT INTO " + table + "(build_id," + identity + ",meaning_hash) VALUES "
                        + String.join(",", Collections.nCopies(entries.size(), "(?,?,?)"))
                        + " ON CONFLICT DO NOTHING RETURNING " + identity,
                (rs, index) -> rs.getString(1),
                args.toArray());
    }

    private Map<String, String> hashes(UUID build, List<String> ids, String table, String identity) {
        var result = new HashMap<String, String>();
        var args = new ArrayList<Object>();
        args.add(build);
        args.addAll(ids);
        jdbc.query(
                "SELECT " + identity + ",meaning_hash FROM " + table + " WHERE build_id=? AND " + identity + " IN ("
                        + placeholders(ids.size()) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getString(2)),
                args.toArray());
        return result;
    }

    private Map<Long, State> lockStates(UUID build, List<Long> payments) {
        var result = new HashMap<Long, State>();
        if (payments.isEmpty()) return result;
        String empty = ProjectionJson.write(PaymentProjectionState.empty());
        var insert = new ArrayList<Object>();
        for (Long payment : payments) {
            insert.add(build);
            insert.add(payment);
            insert.add(empty);
            insert.add(SettlementFact.hash(empty));
        }
        jdbc.update(
                "INSERT INTO p_settlement_projection_payment_states"
                        + "(build_id,payment_id,payload,pending,revision,meaning_hash) VALUES "
                        + String.join(",", Collections.nCopies(payments.size(), "(?,?,?,false,0,?)"))
                        + " ON CONFLICT DO NOTHING",
                insert.toArray());
        var args = new ArrayList<Object>();
        args.add(build);
        args.addAll(payments);
        jdbc.query(
                "SELECT payment_id,payload,revision FROM p_settlement_projection_payment_states "
                        + "WHERE build_id=? AND payment_id IN (" + placeholders(payments.size())
                        + ") ORDER BY payment_id FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(
                        rs.getLong(1),
                        new State(ProjectionJson.read(rs.getString(2), PaymentProjectionState.class), rs.getLong(3))),
                args.toArray());
        return result;
    }

    private void mergeTotals(UUID build, Map<String, SettlementTotals> deltas) {
        var updates = new ArrayList<Object[]>();
        for (String key : deltas.keySet().stream().sorted().toList()) {
            SettlementTotals delta = deltas.get(key);
            String[] parts = key.split(":");
            updates.add(new Object[] {
                build,
                key,
                Long.parseLong(parts[0]),
                Long.parseLong(parts[1]),
                java.time.LocalDate.parse(parts[2]),
                delta.saleCount(),
                delta.saleSellerAmount(),
                delta.refundCount(),
                delta.refundSellerAmount(),
                SettlementFact.hash(key)
            });
        }
        if (!updates.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO p_settlement_projection_totals(build_id,aggregate_key,seller_id,schedule_id,business_date,
                  sale_count,sale_amount,refund_count,refund_amount,revision,meaning_hash)
                VALUES(?,?,?,?,?,?,?,?,?,1,?) ON CONFLICT(build_id,aggregate_key) DO UPDATE SET
                  sale_count=p_settlement_projection_totals.sale_count+excluded.sale_count,
                  sale_amount=p_settlement_projection_totals.sale_amount+excluded.sale_amount,
                  refund_count=p_settlement_projection_totals.refund_count+excluded.refund_count,
                  refund_amount=p_settlement_projection_totals.refund_amount+excluded.refund_amount,
                  revision=p_settlement_projection_totals.revision+1,updated_at=CURRENT_TIMESTAMP
                """, updates);
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }
}
