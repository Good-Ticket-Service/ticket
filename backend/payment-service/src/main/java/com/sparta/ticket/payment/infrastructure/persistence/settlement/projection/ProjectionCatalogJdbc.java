package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.port.settlement.ProjectionOperations;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** source manifest·발행 분기·검증된 조회 세대를 관리한다. 금융 대조 head와 독립이다. */
@Repository
@RequiredArgsConstructor
public class ProjectionCatalogJdbc implements ProjectionOperations {
    private final JdbcTemplate jdbc;
    private final ProjectionFactBatchJdbc factBatch;

    public record Build(UUID id, String mode, String status) {}

    @Transactional
    public UUID create(UUID commandId, String mode, String actor, String reason) {
        if (commandId == null
                || !List.of("D", "B", "C").contains(mode)
                || actor == null
                || actor.isBlank()
                || reason == null
                || reason.isBlank()
                || reason.length() > 500) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        jdbc.queryForMap("SELECT generation FROM p_settlement_projection_head WHERE id=1 FOR UPDATE");
        var existing =
                jdbc.queryForList("SELECT mode,actor,reason FROM p_settlement_projection_builds WHERE id=?", commandId);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (!mode.equals(row.get("mode")) || !actor.equals(row.get("actor")) || !reason.equals(row.get("reason")))
                throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
            return commandId;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_settlement_projection_builds WHERE status IN ('BUILDING','VERIFYING','READY'))",
                Boolean.class))) throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        jdbc.update(
                "INSERT INTO p_settlement_projection_builds(id,mode,status,actor,reason) VALUES(?,?,'BUILDING',?,?)",
                commandId,
                mode,
                actor,
                reason);
        return commandId;
    }

    public List<Build> running() {
        return jdbc.query(
                "SELECT id,mode,status FROM p_settlement_projection_builds WHERE status IN ('BUILDING','VERIFYING','READY','ACTIVE') ORDER BY created_at",
                (rs, row) -> new Build(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
    }

    /** 기존 원천을 RR로 읽는다. 불변 payload는 별도 TX의 작은 batch로 보존한다. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public long materialize(UUID buildId) {
        var build = jdbc.queryForMap(
                "SELECT source_snapshot FROM p_settlement_projection_builds WHERE id=? FOR UPDATE", buildId);
        if (build.get("source_snapshot") != null) return 0;
        String snapshot = jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
        var batch = new ArrayList<Object[]>();
        long[] count = {0};
        jdbc.query(
                connection -> {
                    var statement = connection.prepareStatement(SettlementSourceJdbc.SOURCE + " ORDER BY t.id");
                    statement.setFetchSize(1000);
                    return statement;
                },
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String key = rs.getString("fact_key");
                    UUID eventId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
                    var fact = new SettlementFact(
                            eventId.toString(),
                            key,
                            SettlementFact.Kind.valueOf(rs.getString("source_type")),
                            rs.getString("action"),
                            rs.getLong("payment_id"),
                            rs.getObject("refund_id", Long.class),
                            rs.getLong("seller_id"),
                            rs.getLong("schedule_id"),
                            rs.getTimestamp("occurred_at").toLocalDateTime(),
                            rs.getLong("seller_amount"),
                            rs.getString("policy_version"));
                    batch.add(new Object[] {
                        key,
                        eventId,
                        rs.getLong("journal_id"),
                        fact.paymentId(),
                        fact.sellerId(),
                        fact.scheduleId(),
                        fact.businessDate(),
                        fact.kind().name(),
                        fact.sellerAmount(),
                        fact.meaningHash(),
                        ProjectionJson.write(fact)
                    });
                    count[0]++;
                    if (batch.size() == 1000) factBatch.save(batch);
                });
        factBatch.save(batch);
        Long eligible = jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE source_type IN ('SALE','REFUND')", Long.class);
        if (eligible == null || eligible != count[0]) throw new IllegalStateException("금융 원천의 귀속 메타데이터가 누락됐다");
        jdbc.update("UPDATE p_settlement_projection_builds SET source_snapshot=? WHERE id=?", snapshot, buildId);
        return count[0];
    }

    /** 작은 ID의 늦은 커밋도 포함한다. fanout cursor는 금융 대조 cursor와 공유하지 않는다. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int fanout(UUID buildId) {
        var row = jdbc.queryForMap(
                "SELECT status,fanout_snapshot FROM p_settlement_projection_builds WHERE id=? FOR UPDATE", buildId);
        if (!List.of("BUILDING", "ACTIVE").contains(row.get("status"))) return 0;
        String previous = (String) row.get("fanout_snapshot");
        String snapshot = jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
        String selection = previous == null
                ? ""
                : " WHERE writer_xid>=pg_snapshot_xmin(?::pg_snapshot) AND NOT pg_visible_in_snapshot(writer_xid,?::pg_snapshot)";
        Object[] arguments = previous == null ? new Object[] {buildId} : new Object[] {buildId, previous, previous};
        int inserted = jdbc.update(
                "INSERT INTO p_settlement_projection_deliveries(build_id,fact_key,seller_id,schedule_id,business_date) "
                        + "SELECT ?,fact_key,seller_id,schedule_id,business_date FROM p_settlement_projection_facts"
                        + selection + " ON CONFLICT DO NOTHING",
                arguments);
        jdbc.update(
                "UPDATE p_settlement_projection_builds SET fanout_snapshot=?,last_fanout_at=CURRENT_TIMESTAMP WHERE id=?",
                snapshot,
                buildId);
        return inserted;
    }

    public record Delivery(UUID buildId, String factKey, long paymentId, String payload, UUID token) {}

    @Transactional
    public List<Delivery> claim(int limit, long leaseMillis) {
        UUID token = UUID.randomUUID();
        return jdbc.query(
                """
                WITH candidates AS (
                  SELECT d.build_id,d.fact_key FROM p_settlement_projection_deliveries d
                  JOIN p_settlement_projection_builds b ON b.id=d.build_id
                  WHERE d.published_at IS NULL AND (d.leased_until IS NULL OR d.leased_until<CURRENT_TIMESTAMP)
                    AND b.mode IN ('B','C') AND b.status IN ('BUILDING','VERIFYING','READY','ACTIVE')
                  ORDER BY d.build_id,d.fact_key FOR UPDATE OF d SKIP LOCKED LIMIT ?
                ), claimed AS (
                  UPDATE p_settlement_projection_deliveries d SET lease_token=?,
                    leased_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond'),attempts=attempts+1
                  FROM candidates c WHERE d.build_id=c.build_id AND d.fact_key=c.fact_key RETURNING d.*)
                SELECT c.build_id,c.fact_key,f.payment_id,f.payload FROM claimed c
                JOIN p_settlement_projection_facts f ON f.fact_key=c.fact_key
                """,
                (rs, n) -> new Delivery(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getString(4), token),
                limit,
                token,
                leaseMillis);
    }

    @Transactional
    public void published(Delivery delivery) {
        publishedBatch(List.of(delivery));
    }

    /** ACK를 확인한 유한 batch만 한 번에 커밋한다. 임대 token이 바뀐 행은 갱신하지 않는다. */
    @Transactional
    public void publishedBatch(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) return;
        jdbc.batchUpdate(
                "UPDATE p_settlement_projection_deliveries SET published_at=CURRENT_TIMESTAMP,lease_token=NULL,leased_until=NULL,last_error=NULL WHERE build_id=? AND fact_key=? AND lease_token=?",
                deliveries.stream()
                        .map(delivery -> new Object[] {delivery.buildId(), delivery.factKey(), delivery.token()})
                        .toList());
    }

    @Transactional
    public void failed(Delivery delivery, String error) {
        jdbc.update(
                "UPDATE p_settlement_projection_deliveries SET last_error=? WHERE build_id=? AND fact_key=? AND lease_token=?",
                error,
                delivery.buildId(),
                delivery.factKey(),
                delivery.token());
    }

    @Transactional
    public void freeze(UUID buildId) {
        int updated = jdbc.update(
                "UPDATE p_settlement_projection_builds SET status='VERIFYING',input_count=(SELECT count(*) FROM p_settlement_projection_deliveries WHERE build_id=?) WHERE id=? AND status='BUILDING' AND fanout_snapshot IS NOT NULL",
                buildId,
                buildId);
        if (updated == 0) throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }

    /** manifest membership와 키별 절대 집계를 각각 비교한다. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public boolean verify(UUID buildId) {
        var build =
                jdbc.queryForMap("SELECT status FROM p_settlement_projection_builds WHERE id=? FOR UPDATE", buildId);
        if (!"VERIFYING".equals(build.get("status"))) return "READY".equals(build.get("status"));
        // Note: 발행 중인 대량 manifest를 tick마다 전체 대조하지 않는다. 부분 인덱스로 미발행부터 확인한다.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_settlement_projection_deliveries WHERE build_id=? AND published_at IS NULL)",
                Boolean.class,
                buildId))) return false;
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_settlement_projection_deliveries WHERE build_id=? AND NOT applied)",
                Boolean.class,
                buildId))) return false;
        // Note: 새 세대의 통계가 적은 시점에도 applied 전체를 membership마다 반복 탐색하지 않는다.
        long missing = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT d.fact_key,f.meaning_hash FROM p_settlement_projection_deliveries d
                  JOIN p_settlement_projection_facts f USING(fact_key) WHERE d.build_id=?
                  EXCEPT SELECT fact_key,meaning_hash FROM p_settlement_projection_applied WHERE build_id=?
                ) missing
                """, Long.class, buildId, buildId);
        // Note: 미적용이 남아 있으면 전체 금액/원장 대조로 Consumer의 build 잠금을 오래 막지 않는다.
        if (missing != 0) return false;
        long extra = jdbc.queryForObject("""
                SELECT count(*) FROM p_settlement_projection_applied a WHERE a.build_id=?
                AND NOT EXISTS(SELECT 1 FROM p_settlement_projection_deliveries d WHERE d.build_id=a.build_id AND d.fact_key=a.fact_key)
                """, Long.class, buildId);
        long errors = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_errors WHERE build_id=? AND NOT resolved",
                Long.class,
                buildId);
        long pending = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_payment_states WHERE build_id=? AND pending",
                Long.class,
                buildId);
        if (extra + errors + pending != 0) return false;
        long differences = jdbc.queryForObject("""
                WITH expected AS (
                  SELECT f.seller_id||':'||f.schedule_id||':'||f.business_date AS aggregate_key,
                    count(*) FILTER(WHERE f.kind='SALE') AS sale_count,coalesce(sum(f.amount) FILTER(WHERE f.kind='SALE'),0) AS sale_amount,
                    count(*) FILTER(WHERE f.kind='REFUND') AS refund_count,coalesce(sum(f.amount) FILTER(WHERE f.kind='REFUND'),0) AS refund_amount
                  FROM p_settlement_projection_deliveries d JOIN p_settlement_projection_facts f ON f.fact_key=d.fact_key
                  WHERE d.build_id=? GROUP BY f.seller_id,f.schedule_id,f.business_date
                ), actual AS (SELECT * FROM p_settlement_projection_totals WHERE build_id=?)
                SELECT count(*) FROM expected e FULL JOIN actual a USING(aggregate_key)
                WHERE e.aggregate_key IS NULL OR a.aggregate_key IS NULL OR e.sale_count<>a.sale_count
                  OR e.sale_amount<>a.sale_amount OR e.refund_count<>a.refund_count OR e.refund_amount<>a.refund_amount
                """, Long.class, buildId, buildId);
        // Note: 추출기 오류를 정답으로 삼지 않도록 원장과 고정 SALE 금액도 별도로 대조한다.
        long sourceDifferences = jdbc.queryForObject("""
                SELECT count(*) FROM p_settlement_projection_deliveries d
                JOIN p_settlement_projection_facts f ON f.fact_key=d.fact_key
                LEFT JOIN p_ledger_transactions t ON t.id=f.journal_id
                LEFT JOIN p_payments p ON p.id=t.payment_id
                LEFT JOIN p_settlement_items sale ON sale.payment_id=t.payment_id AND sale.kind='SALE'
                WHERE d.build_id=? AND (t.id IS NULL OR p.id IS NULL OR sale.id IS NULL
                  OR f.fact_key<>('v1:'||t.source_type||':'||t.source_id||':'||t.action)
                  OR f.payment_id<>t.payment_id OR f.seller_id<>t.seller_id OR f.seller_id<>p.seller_id
                  OR f.schedule_id<>p.schedule_id OR f.business_date<>t.occurred_at::date
                  OR d.seller_id<>f.seller_id OR d.schedule_id<>f.schedule_id OR d.business_date<>f.business_date
                  OR f.amount IS DISTINCT FROM sale.seller_amount OR f.kind<>t.source_type
                  OR f.payload::jsonb->>'policyVersion' IS DISTINCT FROM t.policy_version
                  OR (f.payload::jsonb->>'occurredAt')::timestamp IS DISTINCT FROM t.occurred_at
                  OR f.amount IS DISTINCT FROM (
                    SELECT sum(CASE WHEN t.source_type='SALE' THEN e.credit-e.debit ELSE e.debit-e.credit END)
                    FROM p_ledger_entries e WHERE e.transaction_id=t.id AND e.account=
                    CASE WHEN t.action='RECEIVABLE' THEN 'SELLER_RECEIVABLE' ELSE 'SELLER_PAYABLE' END))
                """, Long.class, buildId);
        if (missing + extra + errors + pending + differences + sourceDifferences != 0) return false;
        jdbc.update(
                "UPDATE p_settlement_projection_builds SET status='READY',verified_at=CURRENT_TIMESTAMP,error=NULL WHERE id=?",
                buildId);
        return true;
    }

    @Transactional
    public long activate(UUID buildId, long expectedGeneration) {
        var head =
                jdbc.queryForMap("SELECT generation,build_id FROM p_settlement_projection_head WHERE id=1 FOR UPDATE");
        long generation = ((Number) head.get("generation")).longValue();
        jdbc.queryForList(
                "SELECT id FROM p_settlement_projection_builds WHERE id=? OR id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1) ORDER BY id FOR UPDATE",
                buildId);
        var build =
                jdbc.queryForMap("SELECT status FROM p_settlement_projection_builds WHERE id=? FOR UPDATE", buildId);
        if (generation != expectedGeneration || !"READY".equals(build.get("status")))
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        if (head.get("build_id") != null)
            jdbc.update("UPDATE p_settlement_projection_builds SET status='RETIRED' WHERE id=?", head.get("build_id"));
        jdbc.update("UPDATE p_settlement_projection_builds SET status='ACTIVE' WHERE id=?", buildId);
        jdbc.update(
                "UPDATE p_settlement_projection_head SET generation=generation+1,build_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                buildId);
        return generation + 1;
    }

    public long generation() {
        return jdbc.queryForObject("SELECT generation FROM p_settlement_projection_head WHERE id=1", Long.class);
    }

    public Map<String, Object> progress(UUID id) {
        var rows = jdbc.queryForList("""
                SELECT b.*,(SELECT count(*) FROM p_settlement_projection_deliveries d WHERE d.build_id=b.id) AS deliveries,
                  (SELECT count(*) FROM p_settlement_projection_deliveries d WHERE d.build_id=b.id AND d.published_at IS NULL) AS unpublished,
                  (SELECT count(*) FROM p_settlement_projection_applied a WHERE a.build_id=b.id) AS applied,
                  (SELECT count(*) FROM p_settlement_projection_errors e WHERE e.build_id=b.id AND NOT resolved) AS errors
                FROM p_settlement_projection_builds b WHERE b.id=?
                """, id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        var result = new java.util.LinkedHashMap<String, Object>();
        rows.get(0).forEach((key, value) -> {
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char letter : key.toCharArray()) {
                if (letter == '_') {
                    upper = true;
                    continue;
                }
                name.append(upper ? Character.toUpperCase(letter) : letter);
                upper = false;
            }
            result.put(name.toString(), value);
        });
        return result;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Result control(
            UUID commandId, UUID buildId, String action, long expectedGeneration, String actor, String reason) {
        if (commandId == null
                || buildId == null
                || action == null
                || !List.of("freeze", "verify", "activate", "resume", "rollback", "discard")
                        .contains(action)
                || expectedGeneration < 0
                || actor == null
                || actor.isBlank()
                || reason == null
                || reason.isBlank()
                || reason.length() > 500) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        String inputHash =
                SettlementFact.hash(buildId + "|" + action + "|" + expectedGeneration + "|" + actor + "|" + reason);
        jdbc.queryForMap("SELECT generation FROM p_settlement_projection_head WHERE id=1 FOR UPDATE");
        var previous = jdbc.queryForList(
                "SELECT input_hash,result FROM p_settlement_projection_commands WHERE command_id=?", commandId);
        if (!previous.isEmpty()) {
            if (!inputHash.equals(previous.get(0).get("input_hash")))
                throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
            return ProjectionJson.read((String) previous.get(0).get("result"), Result.class);
        }
        if (generation() != expectedGeneration) throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        if ("activate".equals(action))
            jdbc.queryForList(
                    "SELECT id FROM p_settlement_projection_builds WHERE id=? OR id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1) ORDER BY id FOR UPDATE",
                    buildId);
        var rows =
                jdbc.queryForList("SELECT status FROM p_settlement_projection_builds WHERE id=? FOR UPDATE", buildId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        String status = (String) rows.get(0).get("status");
        switch (action) {
            case "freeze" -> freeze(buildId);
            case "verify" -> verify(buildId);
            case "activate" -> activate(buildId, expectedGeneration);
            case "resume" -> {
                if (!List.of("VERIFYING", "READY", "FAILED").contains(status))
                    throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
                jdbc.update(
                        "UPDATE p_settlement_projection_builds SET status='BUILDING',verified_at=NULL,error=NULL WHERE id=?",
                        buildId);
            }
            case "rollback" -> {
                if (!"RETIRED".equals(status)) throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
                // Note: 과거 세대를 바로 노출하지 않고 신규 유입을 따라잡아 다시 검증한 뒤 활성화한다.
                jdbc.update(
                        "UPDATE p_settlement_projection_builds SET status='BUILDING',verified_at=NULL,error=NULL WHERE id=?",
                        buildId);
            }
            case "discard" -> {
                if (!List.of("BUILDING", "VERIFYING", "READY").contains(status))
                    throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
                jdbc.update(
                        "UPDATE p_settlement_projection_builds SET status='FAILED',error='OPERATOR_DISCARDED' WHERE id=?",
                        buildId);
            }
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        String next = jdbc.queryForObject(
                "SELECT status FROM p_settlement_projection_builds WHERE id=?", String.class, buildId);
        var result = new Result(
                commandId,
                buildId,
                next,
                generation(),
                List.of("READY", "ACTIVE").contains(next));
        jdbc.update(
                "INSERT INTO p_settlement_projection_commands(command_id,build_id,action,input_hash,actor,reason,result) VALUES(?,?,?,?,?,?,?)",
                commandId,
                buildId,
                action,
                inputHash,
                actor,
                reason,
                ProjectionJson.write(result));
        return result;
    }
}
