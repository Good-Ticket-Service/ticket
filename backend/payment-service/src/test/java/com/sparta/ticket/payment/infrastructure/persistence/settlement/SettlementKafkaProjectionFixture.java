package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.projection.PaymentProjectionState;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 시험 이력의 파생 상태를 한 번만 준비한다. 실제 Consumer의 이력 재생 처리량으로 보고하지 않는다. */
final class SettlementKafkaProjectionFixture {
    private SettlementKafkaProjectionFixture() {}

    static void prepare(JdbcTemplate jdbc, long history, String mode, Map<String, Object> report) {
        if (!List.of("D", "B").contains(mode)) throw new IllegalArgumentException("D/B fixture만 허용한다");
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var tx = new TransactionTemplate(manager);
        var catalog = ProjectionKafkaIntegrationTest.proxy(
                new ProjectionCatalogJdbc(jdbc, ProjectionKafkaIntegrationTest.factBatch(jdbc)), manager);
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM p_payments WHERE id<=? AND created_by='sk363-fixture'", Long.class, history);
        if (count != history) throw new IllegalStateException("합성 이력 전용 복제본이 아니다");
        if ("D".equals(mode)) {
            // Note: 과거 금융 워커 기준본은 보존한다. 새 full-pipeline 복제본의 합성 메타데이터만 보정한다.
            report.put(
                    "fixtureMetadataCorrected",
                    jdbc.update(
                            "UPDATE p_settlement_items s SET schedule_start_at=p.schedule_start_at,eligible_at=timestamp '2026-11-11 00:00' FROM p_payments p WHERE p.id=s.payment_id AND p.id<=? AND s.kind='SALE' AND s.created_by='sk363-fixture'",
                            history));
            UUID build = jdbc.queryForObject(
                    "SELECT id FROM p_settlement_projection_builds WHERE mode='D' AND status='BUILDING'", UUID.class);
            catalog.fanout(build);
            tx.executeWithoutResult(status -> initialize(jdbc, build));
            verify(catalog, build, 0);
            report.put("buildId", build);
        } else {
            UUID original = jdbc.queryForObject(
                    "SELECT build_id FROM p_settlement_projection_head WHERE id=1 AND generation=1", UUID.class);
            UUID build = UUID.randomUUID();
            catalog.create(build, "B", "sk363-fixture", "검증된 D 이력을 복제한 동등 집계 fixture");
            // Note: 같은 PostgreSQL cluster에서만 복제한다. 원천/커서의 의미는 금융 기준본과 동일하다.
            jdbc.update(
                    "UPDATE p_settlement_projection_builds b SET source_snapshot=d.source_snapshot FROM p_settlement_projection_builds d WHERE b.id=? AND d.id=?",
                    build,
                    original);
            catalog.fanout(build);
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO p_settlement_projection_payment_states(build_id,payment_id,payload,pending,revision,meaning_hash) SELECT ?,payment_id,payload,pending,revision,meaning_hash FROM p_settlement_projection_payment_states WHERE build_id=?",
                        build,
                        original);
                jdbc.update(
                        "INSERT INTO p_settlement_projection_totals(build_id,aggregate_key,seller_id,schedule_id,business_date,sale_count,sale_amount,refund_count,refund_amount,revision,meaning_hash) SELECT ?,aggregate_key,seller_id,schedule_id,business_date,sale_count,sale_amount,refund_count,refund_amount,revision,meaning_hash FROM p_settlement_projection_totals WHERE build_id=?",
                        build,
                        original);
                copyReceipts(jdbc, build);
            });
            verify(catalog, build, 1);
            report.put("buildId", build);
        }
        report.put("mode", mode);
        report.put(
                "historicalStates",
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_payment_states WHERE build_id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1)",
                        Long.class));
        report.put("keyDifferences", ProjectionLoadDriver.keyDifferences(jdbc));
        if (!Long.valueOf(0).equals(report.get("keyDifferences"))) throw new IllegalStateException("이력 집계 독립 대조 실패");
    }

    private static void verify(ProjectionCatalogJdbc catalog, UUID build, long generation) {
        catalog.freeze(build);
        if (!catalog.verify(build)) throw new IllegalStateException("합성 집계 fixture 검증 실패");
        catalog.activate(build, generation);
    }

    private static void initialize(JdbcTemplate jdbc, UUID build) {
        var batch = new ArrayList<Object[]>();
        long[] payment = {-1};
        PaymentProjectionState[] state = {PaymentProjectionState.empty()};
        long[] revision = {0};
        Runnable flushPayment = () -> {
            if (payment[0] < 0) return;
            if (state[0].pending()) throw new IllegalStateException("이력 fixture에 pending 환불이 있다");
            String payload = ProjectionJson.write(state[0]);
            batch.add(new Object[] {build, payment[0], payload, revision[0], SettlementFact.hash(payload)});
            if (batch.size() == 1000) saveStates(jdbc, batch);
        };
        jdbc.query(
                "SELECT payload FROM p_settlement_projection_facts ORDER BY payment_id,CASE WHEN kind='SALE' THEN 0 ELSE 1 END",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    var fact = ProjectionJson.read(rs.getString(1), SettlementFact.class);
                    if (payment[0] != fact.paymentId()) {
                        flushPayment.run();
                        payment[0] = fact.paymentId();
                        state[0] = PaymentProjectionState.empty();
                        revision[0] = 0;
                    }
                    var decision = state[0].accept(fact);
                    if (decision.error() != null) throw new IllegalStateException("이력 fixture 업무 상태 검증 실패");
                    state[0] = decision.state();
                    revision[0]++;
                });
        flushPayment.run();
        saveStates(jdbc, batch);
        var totals = jdbc.queryForList(
                "SELECT seller_id,schedule_id,business_date,count(*) FILTER(WHERE kind='SALE') sales,coalesce(sum(amount) FILTER(WHERE kind='SALE'),0)::bigint sale_amount,count(*) FILTER(WHERE kind='REFUND') refunds,coalesce(sum(amount) FILTER(WHERE kind='REFUND'),0)::bigint refund_amount FROM p_settlement_projection_facts GROUP BY seller_id,schedule_id,business_date");
        var totalBatch = new ArrayList<Object[]>();
        for (var row : totals) {
            String key = row.get("seller_id") + ":" + row.get("schedule_id") + ":" + row.get("business_date");
            totalBatch.add(new Object[] {
                build,
                key,
                row.get("seller_id"),
                row.get("schedule_id"),
                row.get("business_date"),
                row.get("sales"),
                row.get("sale_amount"),
                row.get("refunds"),
                row.get("refund_amount"),
                SettlementFact.hash(key)
            });
            if (totalBatch.size() == 1000) saveTotals(jdbc, totalBatch);
        }
        saveTotals(jdbc, totalBatch);
        copyReceipts(jdbc, build);
    }

    private static void saveStates(JdbcTemplate jdbc, List<Object[]> batch) {
        insert(
                jdbc,
                batch,
                "INSERT INTO p_settlement_projection_payment_states(build_id,payment_id,payload,revision,meaning_hash,pending) VALUES ",
                "(?,?,?,?,?,false)");
    }

    private static void saveTotals(JdbcTemplate jdbc, List<Object[]> batch) {
        insert(
                jdbc,
                batch,
                "INSERT INTO p_settlement_projection_totals(build_id,aggregate_key,seller_id,schedule_id,business_date,sale_count,sale_amount,refund_count,refund_amount,meaning_hash,revision) VALUES ",
                "(?,?,?,?,?,?,?,?,?,?,1)");
    }

    private static void insert(JdbcTemplate jdbc, List<Object[]> batch, String sql, String placeholders) {
        if (batch.isEmpty()) return;
        var args = new ArrayList<Object>();
        batch.forEach(row -> Collections.addAll(args, row));
        jdbc.update(sql + String.join(",", Collections.nCopies(batch.size(), placeholders)), args.toArray());
        batch.clear();
    }

    private static void copyReceipts(JdbcTemplate jdbc, UUID build) {
        jdbc.update(
                "INSERT INTO p_settlement_projection_event_ids(build_id,event_id,meaning_hash) SELECT ?,event_id::text,meaning_hash FROM p_settlement_projection_facts",
                build);
        jdbc.update(
                "INSERT INTO p_settlement_projection_applied(build_id,fact_key,meaning_hash) SELECT ?,fact_key,meaning_hash FROM p_settlement_projection_facts",
                build);
        jdbc.update(
                "UPDATE p_settlement_projection_deliveries SET published_at=CURRENT_TIMESTAMP,applied=true WHERE build_id=?",
                build);
    }
}
