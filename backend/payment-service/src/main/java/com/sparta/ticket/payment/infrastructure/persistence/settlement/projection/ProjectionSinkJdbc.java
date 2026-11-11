package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 출력 절대값을 세대·revision별로 적용한다. DB 커밋 후에만 consumer offset을 진행한다. */
@Repository
@RequiredArgsConstructor
public class ProjectionSinkJdbc {
    private final JdbcTemplate jdbc;

    @Transactional
    public void apply(List<ProjectionResult> results) {
        var statuses = new java.util.HashMap<UUID, String>();
        // Note: 여러 세대가 한 poll에 섞여도 같은 순서로 잠가 활성화와의 교착을 피한다.
        for (String id : results.stream()
                .map(ProjectionResult::buildId)
                .distinct()
                .sorted()
                .toList()) {
            UUID build = UUID.fromString(id);
            var rows =
                    jdbc.queryForList("SELECT status FROM p_settlement_projection_builds WHERE id=? FOR SHARE", build);
            if (!rows.isEmpty()) statuses.put(build, (String) rows.get(0).get("status"));
        }
        var totals = new TreeMap<String, List<ProjectionResult>>();
        for (ProjectionResult result : results) {
            UUID build = UUID.fromString(result.buildId());
            if (!statuses.containsKey(build) || List.of("RETIRED", "FAILED").contains(statuses.get(build))) continue;
            switch (result.type()) {
                case "RECEIPT" -> error(build, result.factKey(), "UNLINKED_RECEIPT", result.amount());
                case "TOTAL" -> {
                    if (result.factKey() == null || result.meaningHash() == null) {
                        error(build, result.key(), "UNLINKED_TOTAL", result.amount());
                        continue;
                    }
                    totals.computeIfAbsent(build.toString(), ignored -> new ArrayList<>())
                            .add(result);
                }
                case "STATE" -> state(build, result);
                case "ERROR" -> error(build, result.factKey(), result.error(), result.amount());
                default -> throw new IllegalArgumentException("집계 출력 종류가 올바르지 않다");
            }
        }
        totals.forEach((id, batch) -> totalBatch(UUID.fromString(id), batch));
    }

    private void totalBatch(UUID build, List<ProjectionResult> batch) {
        List<String> factKeys = batch.stream()
                .map(ProjectionResult::factKey)
                .distinct()
                .sorted()
                .toList();
        var members = new HashMap<String, Member>();
        jdbc.query(
                "SELECT f.fact_key,f.meaning_hash,f.seller_id||':'||f.schedule_id||':'||f.business_date AS aggregate_key "
                        + "FROM p_settlement_projection_deliveries d JOIN p_settlement_projection_facts f USING(fact_key) "
                        + "WHERE d.build_id=? AND d.fact_key IN (" + placeholders(factKeys.size()) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> members.put(rs.getString(1), new Member(rs.getString(2), rs.getString(3))),
                arguments(build, factKeys));
        var accepted = new ArrayList<ProjectionResult>();
        var latest = new TreeMap<String, ProjectionResult>();
        var revisionHashes = new HashMap<String, String>();
        var memberKeys = new java.util.TreeSet<String>();
        for (ProjectionResult result : batch) {
            Member member = members.get(result.factKey());
            if (member != null
                    && member.key().equals(result.key())
                    && member.hash().equals(result.meaningHash())) memberKeys.add(result.key());
        }
        Map<String, Version> before = memberKeys.isEmpty() ? Map.of() : versions(build, memberKeys);
        for (ProjectionResult result : batch) {
            Member member = members.get(result.factKey());
            if (member == null
                    || !result.meaningHash().equals(member.hash())
                    || !member.key().equals(result.key())) {
                error(build, result.factKey(), "SOURCE_MEMBERSHIP_CONFLICT", result.amount());
                continue;
            }
            if (result.key().split(":", -1).length != 3 || result.revision() <= 0 || result.totals() == null)
                throw new IllegalArgumentException("집계 출력 키·revision이 올바르지 않다");
            Version existing = before.get(result.key());
            if (existing != null
                    && existing.revision() == result.revision()
                    && !existing.hash().equals(result.hash())) {
                error(
                        build,
                        result.key(),
                        "TOTAL_REVISION_CONFLICT",
                        result.totals().netSellerAmount());
                continue;
            }
            String previous = revisionHashes.putIfAbsent(result.key() + "|" + result.revision(), result.hash());
            if (previous != null && !previous.equals(result.hash())) {
                error(
                        build,
                        result.key(),
                        "TOTAL_REVISION_CONFLICT",
                        result.totals().netSellerAmount());
                continue;
            }
            accepted.add(result);
            latest.merge(result.key(), result, (old, next) -> next.revision() > old.revision() ? next : old);
        }
        if (latest.isEmpty()) return;
        var values = new ArrayList<Object>();
        for (ProjectionResult result : latest.values()) {
            String[] key = result.key().split(":", -1);
            SettlementTotals t = result.totals();
            values.addAll(List.of(
                    build,
                    result.key(),
                    Long.parseLong(key[0]),
                    Long.parseLong(key[1]),
                    java.time.LocalDate.parse(key[2]),
                    t.saleCount(),
                    t.saleSellerAmount(),
                    t.refundCount(),
                    t.refundSellerAmount(),
                    result.revision(),
                    result.hash()));
        }
        // Note: 같은 키는 최신 절대값으로 합치고 모든 입력의 적용 확인도 같은 TX에 저장한다.
        jdbc.update(
                """
                INSERT INTO p_settlement_projection_totals(build_id,aggregate_key,seller_id,schedule_id,business_date,
                  sale_count,sale_amount,refund_count,refund_amount,revision,meaning_hash)
                VALUES %s ON CONFLICT(build_id,aggregate_key) DO UPDATE SET
                  sale_count=excluded.sale_count,sale_amount=excluded.sale_amount,refund_count=excluded.refund_count,
                  refund_amount=excluded.refund_amount,revision=excluded.revision,meaning_hash=excluded.meaning_hash,
                  updated_at=CURRENT_TIMESTAMP WHERE p_settlement_projection_totals.revision<excluded.revision
                """.formatted(String.join(
                        ",", java.util.Collections.nCopies(latest.size(), "(?,?,?,?,?,?,?,?,?,?,?)"))),
                values.toArray());
        Map<String, Version> current = versions(build, latest.keySet());
        var receipts = new TreeMap<String, ProjectionResult>();
        for (ProjectionResult result : accepted) {
            Version stored = current.get(result.key());
            if (stored == null) throw new IllegalStateException("집계 절대값 저장이 누락됐다");
            if (stored.revision() == result.revision() && !stored.hash().equals(result.hash()))
                error(
                        build,
                        result.key(),
                        "TOTAL_REVISION_CONFLICT",
                        result.totals().netSellerAmount());
            else receipts.putIfAbsent(result.factKey(), result);
        }
        receiptBatch(build, receipts);
    }

    private void receiptBatch(UUID build, TreeMap<String, ProjectionResult> receipts) {
        if (receipts.isEmpty()) return;
        var values = new ArrayList<Object>();
        receipts.values().forEach(result -> values.addAll(List.of(build, result.factKey(), result.meaningHash())));
        jdbc.update(
                "INSERT INTO p_settlement_projection_applied(build_id,fact_key,meaning_hash) VALUES "
                        + String.join(",", java.util.Collections.nCopies(receipts.size(), "(?,?,?)"))
                        + " ON CONFLICT DO NOTHING",
                values.toArray());
        var stored = new HashMap<String, String>();
        jdbc.query(
                "SELECT fact_key,meaning_hash FROM p_settlement_projection_applied WHERE build_id=? AND fact_key IN ("
                        + placeholders(receipts.size()) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> stored.put(rs.getString(1), rs.getString(2)),
                arguments(build, receipts.keySet()));
        var confirmed = new java.util.TreeSet<String>();
        receipts.forEach((key, result) -> {
            if (!stored.containsKey(key)) throw new IllegalStateException("금액과 함께 저장할 적용 확인이 누락됐다");
            if (!result.meaningHash().equals(stored.get(key))) error(build, key, "RECEIPT_CONFLICT", result.amount());
            else confirmed.add(key);
        });
        markApplied(build, confirmed);
    }

    /** Note: 적용 표시는 receipt·금액과 같은 TX에서만 갱신하는 조회용 파생 상태다. */
    void markApplied(UUID build, java.util.Collection<String> keys) {
        if (keys.isEmpty()) return;
        Object[] args = arguments(build, keys);
        String filter = " WHERE build_id=? AND fact_key IN (" + placeholders(keys.size()) + ")";
        jdbc.update("UPDATE p_settlement_projection_deliveries SET applied=true" + filter + " AND NOT applied", args);
        Long present = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_deliveries" + filter + " AND applied", Long.class, args);
        if (present == null || present != keys.size()) throw new IllegalStateException("적용 확인과 조회용 미처리 상태가 불일치한다");
    }

    private Map<String, Version> versions(UUID build, java.util.Collection<String> keys) {
        var versions = new HashMap<String, Version>();
        jdbc.query(
                "SELECT aggregate_key,revision,meaning_hash FROM p_settlement_projection_totals WHERE build_id=? "
                        + "AND aggregate_key IN (" + placeholders(keys.size()) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> versions.put(rs.getString(1), new Version(rs.getLong(2), rs.getString(3))),
                arguments(build, keys));
        return versions;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] arguments(UUID build, java.util.Collection<String> keys) {
        var arguments = new ArrayList<Object>();
        arguments.add(build);
        arguments.addAll(keys);
        return arguments.toArray();
    }

    private record Member(String hash, String key) {}

    private record Version(long revision, String hash) {}

    private void state(UUID build, ProjectionResult result) {
        long paymentId = Long.parseLong(result.key());
        if (paymentId <= 0 || result.revision() <= 0 || (result.amount() != 0 && result.amount() != 1))
            throw new IllegalArgumentException("집계 보류 상태가 올바르지 않다");
        jdbc.update("""
                INSERT INTO p_settlement_projection_payment_states(build_id,payment_id,payload,pending,revision,meaning_hash)
                VALUES(?,?,'{}',?,?,?) ON CONFLICT(build_id,payment_id) DO UPDATE SET pending=excluded.pending,
                  revision=excluded.revision,meaning_hash=excluded.meaning_hash
                WHERE p_settlement_projection_payment_states.revision<excluded.revision
                """, build, paymentId, result.amount() == 1, result.revision(), result.hash());
        var current = jdbc.queryForMap(
                "SELECT revision,meaning_hash FROM p_settlement_projection_payment_states WHERE build_id=? AND payment_id=?",
                build,
                paymentId);
        if (((Number) current.get("revision")).longValue() == result.revision()
                && !result.hash().equals(current.get("meaning_hash")))
            error(build, result.key(), "STATE_REVISION_CONFLICT", result.amount());
    }

    public void error(UUID build, String key, String reason, long amount) {
        jdbc.update(
                "INSERT INTO p_settlement_projection_errors(build_id,fact_key,reason,amount) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                build,
                key,
                reason,
                amount);
    }
}
