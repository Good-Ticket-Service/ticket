package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.dto.payment.SettlementPostingCommand;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import com.sparta.ticket.payment.application.service.payment.InvalidSettlementPostingException;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** job→incident→command 순으로 잠근다. 운영 입력 JSON 대신 영속 source만 재전달한다. */
@Repository
@RequiredArgsConstructor
public class FinancialConsumerRecoveryJdbc implements FinancialConsumerRecovery {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Incident quarantine(Source source, String reason, SettlementPostingCommand trusted) {
        Long jobId = null;
        if (trusted != null) {
            var jobs = jdbc.queryForList("SELECT * FROM p_settlement_jobs WHERE id=? FOR UPDATE", trusted.jobId());
            if (!jobs.isEmpty() && sameSource(jobs.get(0), trusted)) {
                jobId = trusted.jobId();
                jdbc.update("""
                    UPDATE p_settlement_jobs SET status='BLOCKED',attempts=attempts+1,last_error=?,
                      updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_by='SYSTEM' WHERE id=? AND status='PENDING'
                    """, reason, jobId);
            }
        }
        var row = jdbc.queryForMap(
                """
                INSERT INTO p_financial_consumer_incidents(stage,group_id,source_topic,source_partition,
                  source_offset,payload_hash,reason,source_job_id) VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(stage,group_id,source_topic,source_partition,source_offset)
                DO UPDATE SET deliveries=p_financial_consumer_incidents.deliveries+1,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                WHERE p_financial_consumer_incidents.payload_hash=excluded.payload_hash
                RETURNING incident_id,status,source_job_id
                """,
                source.stage(),
                source.group(),
                source.topic(),
                source.partition(),
                source.offset(),
                source.payloadHash(),
                reason,
                jobId);
        if (jobId != null && trusted.replayCommandId() != null) {
            var previous = jdbc.queryForList("""
                    SELECT i.incident_id FROM p_financial_replay_commands c JOIN p_financial_consumer_incidents i ON i.incident_id=c.incident_id
                    WHERE c.command_id=? AND i.source_job_id=?
                    """, trusted.replayCommandId(), jobId);
            if (!previous.isEmpty()) {
                UUID original = (UUID) previous.get(0).get("incident_id");
                jdbc.queryForMap(
                        "SELECT incident_id FROM p_financial_consumer_incidents WHERE incident_id=? FOR UPDATE",
                        original);
                jdbc.update(
                        "UPDATE p_financial_replay_commands SET status='DENIED',explanation='REPLAY_FAILED',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE command_id=? AND status IN ('REQUESTED','PUBLISHED')",
                        trusted.replayCommandId());
                jdbc.update(
                        "UPDATE p_financial_consumer_incidents SET status='OPEN',resolved_at=NULL,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=?",
                        original);
            }
        }
        return new Incident((UUID) row.get("incident_id"), (String) row.get("status"), (Long) row.get("source_job_id"));
    }

    private static boolean sameSource(Map<String, Object> row, SettlementPostingCommand command) {
        return "KAFKA".equals(row.get("transport"))
                && Objects.equals(row.get("event_id"), command.eventId())
                && Objects.equals(row.get("payment_id"), command.paymentId())
                && Objects.equals(row.get("reason"), command.reason().name())
                && ((Timestamp) row.get("created_at")).toLocalDateTime().equals(command.occurredAt());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deadLetterConfirmed(UUID incidentId) {
        jdbc.update(
                "UPDATE p_financial_consumer_incidents SET dlt_confirmed_at=coalesce(dlt_confirmed_at,(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')),updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=?",
                incidentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateReplay(UUID commandId, long jobId) {
        if (commandId == null) return;
        var reference = jdbc.queryForList("""
                SELECT i.incident_id FROM p_financial_replay_commands c JOIN p_financial_consumer_incidents i ON i.incident_id=c.incident_id
                WHERE c.command_id=? AND c.action='REPLAY' AND i.source_job_id=?
                """, commandId, jobId);
        if (reference.isEmpty()) throw new InvalidSettlementPostingException("INVALID_REPLAY_SOURCE");
        jdbc.queryForMap(
                "SELECT incident_id FROM p_financial_consumer_incidents WHERE incident_id=? FOR UPDATE",
                reference.get(0).get("incident_id"));
        String status = jdbc.queryForObject(
                "SELECT status FROM p_financial_replay_commands WHERE command_id=? FOR UPDATE",
                String.class,
                commandId);
        if (!List.of("REQUESTED", "PUBLISHED", "DONE").contains(status))
            throw new InvalidSettlementPostingException("INVALID_REPLAY_STATE");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replayApplied(UUID commandId, long jobId) {
        if (commandId == null) return;
        if (unposted(jobId)) {
            jdbc.update(
                    "UPDATE p_financial_replay_commands SET status='DENIED',explanation='UNPOSTED_REQUIRES_BACKFILL',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE command_id=? AND status IN ('REQUESTED','PUBLISHED')",
                    commandId);
            jdbc.update(
                    "UPDATE p_financial_consumer_incidents SET status='OPEN',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE source_job_id=? AND status='REPLAY_REQUESTED'",
                    jobId);
            return;
        }
        jdbc.update(
                "UPDATE p_financial_replay_commands SET status='DONE',explanation='FINANCIAL_APPLIED',lease_token=NULL,lease_until=NULL,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE command_id=? AND status IN ('REQUESTED','PUBLISHED')",
                commandId);
        jdbc.update(
                "UPDATE p_financial_consumer_incidents SET status='RESOLVED',resolved_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE source_job_id=? AND status<>'RESOLVED'",
                jobId);
    }

    private boolean unposted(long jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_settlement_jobs j JOIN p_settlement_items s ON s.payment_id=j.payment_id AND s.kind='SALE' WHERE j.id=? AND s.status='UNPOSTED')",
                Boolean.class,
                jobId));
    }

    @Override
    public List<Map<String, Object>> list(long after, int limit, String status) {
        return jdbc.queryForList("""
                SELECT id,incident_id AS "incidentId",stage,group_id AS "group",source_topic AS "sourceTopic",
                  source_partition AS "sourcePartition",source_offset AS "sourceOffset",payload_hash AS "payloadHash",
                  reason,source_job_id AS "sourceJobId",source_build_id AS "sourceBuildId",source_fact_key AS "sourceFactKey",
                  status,deliveries,dlt_confirmed_at AS "dltConfirmedAt",created_at AS "createdAt",updated_at AS "updatedAt"
                FROM p_financial_consumer_incidents WHERE id>? AND (?::varchar IS NULL OR status=?) ORDER BY id LIMIT ?
                """, after, status, status, limit);
    }

    @Override
    public Map<String, Object> command(UUID commandId) {
        var rows = jdbc.queryForList("""
                SELECT command_id AS "commandId",incident_id AS "incidentId",action,actor,reason,status,explanation,
                  attempts,published_at AS "publishedAt",last_error AS "lastError",created_at AS "createdAt",updated_at AS "updatedAt"
                FROM p_financial_replay_commands WHERE command_id=?
                """, commandId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    @Override
    @Transactional
    public Outcome request(UUID commandId, UUID incidentId, String action, String actor, String reason) {
        if (commandId == null
                || incidentId == null
                || !List.of("REPLAY", "RESOLVE").contains(action)
                || actor == null
                || actor.isBlank()
                || actor.length() > 200
                || reason == null
                || reason.isBlank()
                || reason.length() > 500) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        String hash = SettlementFact.hash(incidentId + "|" + action + "|" + actor + "|" + reason);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", commandId.toString());
        var old = jdbc.queryForList(
                "SELECT input_hash,status,explanation FROM p_financial_replay_commands WHERE command_id=?", commandId);
        if (!old.isEmpty()) {
            if (!hash.equals(old.get(0).get("input_hash")))
                throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
            return new Outcome(commandId, (String) old.get(0).get("status"), (String)
                    old.get(0).get("explanation"));
        }
        var observed = jdbc.queryForList(
                "SELECT source_job_id,source_build_id,source_fact_key FROM p_financial_consumer_incidents WHERE incident_id=?",
                incidentId);
        if (observed.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        UUID buildId = (UUID) observed.get(0).get("source_build_id");
        if (buildId != null) {
            var build = jdbc.queryForMap(
                    "SELECT mode,status FROM p_settlement_projection_builds WHERE id=? FOR SHARE", buildId);
            String factKey = (String) observed.get(0).get("source_fact_key");
            if (factKey != null) projectionLock(buildId, factKey);
            var projectionIncident = jdbc.queryForMap(
                    "SELECT * FROM p_financial_consumer_incidents WHERE incident_id=? FOR UPDATE", incidentId);
            return requestProjection(
                    commandId, incidentId, action, actor, reason, hash, projectionIncident, buildId, build);
        }
        Long jobId = (Long) observed.get(0).get("source_job_id");
        Map<String, Object> job =
                jobId == null ? null : jdbc.queryForMap("SELECT * FROM p_settlement_jobs WHERE id=? FOR UPDATE", jobId);
        var incident = jdbc.queryForMap(
                "SELECT * FROM p_financial_consumer_incidents WHERE incident_id=? FOR UPDATE", incidentId);
        String explanation;
        String state;
        String payload = null;
        String topic = null;
        String key = null;
        if ("RESOLVED".equals(incident.get("status"))) {
            state = "DONE";
            explanation = "ALREADY_RESOLVED";
        } else if (job != null && unposted(jobId)) {
            state = "DENIED";
            explanation = "UNPOSTED_REQUIRES_BACKFILL";
        } else if (incident.get("source_build_id") != null) {
            state = "DENIED";
            explanation = "PROJECTION_REPLAY_NOT_READY";
        } else if ("RESOLVE".equals(action)) {
            if (job == null || "DONE".equals(job.get("status"))) {
                state = "DONE";
                explanation = "OPERATOR_CONFIRMED";
                resolve(incidentId);
            } else {
                state = "DENIED";
                explanation = "FINANCIAL_UNFINISHED";
            }
        } else if (job == null || !"POSTING".equals(incident.get("stage"))) {
            state = "DENIED";
            explanation = "NO_TRUSTED_SOURCE";
        } else if ("DONE".equals(job.get("status"))) {
            state = "DONE";
            explanation = "ALREADY_APPLIED";
            resolve(incidentId);
        } else if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_financial_replay_commands WHERE incident_id=? AND status IN ('REQUESTED','PUBLISHED'))",
                Boolean.class,
                incidentId))) {
            state = "DENIED";
            explanation = "REPLAY_IN_FLIGHT";
        } else {
            state = "REQUESTED";
            explanation = "REPLAY_REQUESTED";
            payload = jdbc.queryForObject(
                    "SELECT (?::jsonb||jsonb_build_object('replayCommandId',?::uuid::text))::text",
                    String.class,
                    job.get("event_payload"),
                    commandId);
            topic = (String) incident.get("source_topic");
            key = job.get("payment_id").toString();
            jdbc.update(
                    "UPDATE p_settlement_jobs SET status='PENDING',done_at=NULL,last_error=NULL,next_attempt_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_by=? WHERE id=? AND status IN ('PENDING','BLOCKED')",
                    actor,
                    jobId);
            jdbc.update(
                    "UPDATE p_financial_consumer_incidents SET status='REPLAY_REQUESTED',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=?",
                    incidentId);
        }
        jdbc.update("""
                INSERT INTO p_financial_replay_commands(command_id,incident_id,action,input_hash,actor,reason,status,
                  explanation,target_topic,message_key,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, commandId, incidentId, action, hash, actor, reason, state, explanation, topic, key, payload);
        return new Outcome(commandId, state, explanation);
    }

    private void resolve(UUID incidentId) {
        jdbc.update(
                "UPDATE p_financial_consumer_incidents SET status='RESOLVED',resolved_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=?",
                incidentId);
    }

    private void projectionLock(UUID build, String factKey) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,1))", build + "|" + factKey);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Incident quarantineProjection(
            Source source, UUID build, String factKey, String reason, UUID replayCommandId) {
        var active = jdbc.queryForList(
                "SELECT id FROM p_settlement_projection_builds WHERE id=? AND status IN ('BUILDING','VERIFYING','READY','ACTIVE') FOR SHARE",
                build);
        if (active.isEmpty())
            throw new com.sparta.ticket.payment.domain.model.settlement.projection.InactiveProjectionException();
        var facts = factKey == null
                ? List.<Map<String, Object>>of()
                : jdbc.queryForList(
                        "SELECT f.fact_key,f.amount FROM p_settlement_projection_deliveries d JOIN p_settlement_projection_facts f USING(fact_key) WHERE d.build_id=? AND f.fact_key=?",
                        build,
                        factKey);
        String sourceKey = facts.isEmpty() ? null : factKey;
        if (sourceKey != null) projectionLock(build, sourceKey);
        var row = jdbc.queryForMap(
                """
                INSERT INTO p_financial_consumer_incidents(stage,group_id,source_topic,source_partition,source_offset,
                  payload_hash,reason,source_build_id,source_fact_key) VALUES('PROJECTION',?,?,?,?,?,?,?,?)
                ON CONFLICT(stage,group_id,source_topic,source_partition,source_offset)
                DO UPDATE SET deliveries=p_financial_consumer_incidents.deliveries+1,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                WHERE p_financial_consumer_incidents.payload_hash=excluded.payload_hash RETURNING incident_id,status
                """,
                source.group(),
                source.topic(),
                source.partition(),
                source.offset(),
                source.payloadHash(),
                reason,
                build,
                sourceKey);
        String errorKey = sourceKey == null
                ? projectionRawKey(source.group(), source.topic(), source.partition(), source.offset())
                : sourceKey;
        long amount = facts.isEmpty() ? 0 : ((Number) facts.get(0).get("amount")).longValue();
        jdbc.update(
                "INSERT INTO p_settlement_projection_errors(build_id,fact_key,reason,amount) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                build,
                errorKey,
                reason,
                amount);
        if (sourceKey != null && replayCommandId != null) {
            var original = jdbc.queryForList(
                    "SELECT i.incident_id FROM p_financial_replay_commands c JOIN p_financial_consumer_incidents i ON i.incident_id=c.incident_id WHERE c.command_id=? AND i.source_build_id=? AND i.source_fact_key=?",
                    replayCommandId,
                    build,
                    sourceKey);
            if (!original.isEmpty()) {
                UUID previous = (UUID) original.get(0).get("incident_id");
                jdbc.queryForMap(
                        "SELECT incident_id FROM p_financial_consumer_incidents WHERE incident_id=? FOR UPDATE",
                        previous);
                jdbc.update(
                        "UPDATE p_financial_replay_commands SET status='DENIED',explanation='REPLAY_FAILED',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE command_id=? AND status IN ('REQUESTED','PUBLISHED')",
                        replayCommandId);
                jdbc.update(
                        "UPDATE p_financial_consumer_incidents SET status='OPEN',resolved_at=NULL,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=?",
                        previous);
            }
        }
        return new Incident((UUID) row.get("incident_id"), (String) row.get("status"), null);
    }

    private static String projectionRawKey(String group, String topic, int partition, long offset) {
        return "kafka:" + SettlementFact.hash(group + "|" + topic + "|" + partition + "|" + offset);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateProjectionReplay(UUID commandId, UUID build, String factKey) {
        Boolean valid = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_financial_replay_commands c JOIN p_financial_consumer_incidents i ON i.incident_id=c.incident_id WHERE c.command_id=? AND c.action='REPLAY' AND c.status IN ('REQUESTED','PUBLISHED','DONE') AND i.source_build_id=? AND i.source_fact_key=?)",
                Boolean.class,
                commandId,
                build,
                factKey);
        if (!Boolean.TRUE.equals(valid))
            throw new com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionFactConflictException(
                    factKey, "INVALID_REPLAY_SOURCE");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void projectionFactsApplied(UUID build, java.util.Collection<String> factKeys) {
        if (factKeys.isEmpty()) return;
        var arguments = new java.util.ArrayList<Object>();
        arguments.add(build);
        var requested = factKeys.stream().distinct().sorted().toList();
        arguments.addAll(requested);
        var affected = jdbc.queryForList(
                "SELECT DISTINCT source_fact_key FROM p_financial_consumer_incidents WHERE source_build_id=? AND status<>'RESOLVED' AND source_fact_key IN ("
                        + String.join(",", java.util.Collections.nCopies(requested.size(), "?"))
                        + ") ORDER BY source_fact_key",
                String.class,
                arguments.toArray());
        // Note: 정상 묶음은 미해결 source 검색 한 번으로 끝낸다. 사건마다 운영 조회를 반복하지 않는다.
        for (String key : affected) {
            projectionLock(build, key);
            if (!projectionHealthy(build, key)) continue;
            var incidents = jdbc.queryForList("""
                    SELECT i.incident_id FROM p_financial_consumer_incidents i WHERE i.source_build_id=? AND i.source_fact_key=?
                      AND i.status<>'RESOLVED' AND EXISTS(SELECT 1 FROM p_financial_replay_commands c WHERE c.incident_id=i.incident_id AND c.status IN ('REQUESTED','PUBLISHED'))
                    ORDER BY i.id FOR UPDATE
                    """, build, key);
            for (var incident : incidents) {
                UUID id = (UUID) incident.get("incident_id");
                jdbc.update(
                        "UPDATE p_financial_replay_commands SET status='DONE',explanation='PROJECTION_APPLIED',lease_token=NULL,lease_until=NULL,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=? AND status IN ('REQUESTED','PUBLISHED')",
                        id);
                resolve(id);
            }
            clearProjectionError(build, key);
        }
    }

    private boolean projectionHealthy(UUID build, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM p_settlement_projection_applied a JOIN p_settlement_projection_facts f USING(fact_key)
                  JOIN p_settlement_projection_payment_states s ON s.build_id=a.build_id AND s.payment_id=f.payment_id
                  WHERE a.build_id=? AND a.fact_key=? AND a.meaning_hash=f.meaning_hash AND
                    CASE WHEN f.kind='SALE' THEN (s.payload::jsonb->>'saleApplied')::boolean
                      AND (s.payload::jsonb->'sale')-'eventId'=f.payload::jsonb-'eventId'
                    ELSE (s.payload::jsonb->>'refundApplied')::boolean
                      AND (s.payload::jsonb->'refund')-'eventId'=f.payload::jsonb-'eventId' END)
                """, Boolean.class, build, key));
    }

    private void clearProjectionError(UUID build, String key) {
        jdbc.update(
                "UPDATE p_settlement_projection_errors e SET resolved=true WHERE e.build_id=? AND e.fact_key=? AND NOT EXISTS(SELECT 1 FROM p_financial_consumer_incidents i WHERE i.source_build_id=e.build_id AND i.source_fact_key=e.fact_key AND i.status<>'RESOLVED')",
                build,
                key);
    }

    private Outcome requestProjection(
            UUID commandId,
            UUID incidentId,
            String action,
            String actor,
            String reason,
            String hash,
            Map<String, Object> incident,
            UUID build,
            Map<String, Object> buildInfo) {
        String key = (String) incident.get("source_fact_key");
        String state = "DENIED";
        String explanation = "PROJECTION_REPLAY_REQUIRED";
        String payload = null;
        String topic = null;
        String messageKey = null;
        if ("RESOLVED".equals(incident.get("status"))) {
            state = "DONE";
            explanation = "ALREADY_RESOLVED";
        } else if (key == null) {
            if ("RESOLVE".equals(action)) {
                state = "DONE";
                explanation = "OPERATOR_CONFIRMED";
                resolve(incidentId);
                String rawKey = projectionRawKey(
                        (String) incident.get("group_id"),
                        (String) incident.get("source_topic"),
                        ((Number) incident.get("source_partition")).intValue(),
                        ((Number) incident.get("source_offset")).longValue());
                jdbc.update(
                        "UPDATE p_settlement_projection_errors SET resolved=true WHERE build_id=? AND fact_key=?",
                        build,
                        rawKey);
            } else explanation = "NO_TRUSTED_SOURCE";
        } else if (projectionHealthy(build, key)) {
            state = "DONE";
            explanation = "SOURCE_ALREADY_APPLIED";
            resolve(incidentId);
            clearProjectionError(build, key);
            jdbc.update(
                    "UPDATE p_financial_replay_commands SET status='DONE',explanation='SOURCE_ALREADY_APPLIED',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=? AND status IN ('REQUESTED','PUBLISHED')",
                    incidentId);
        } else if (List.of("RETIRED", "FAILED").contains(buildInfo.get("status"))) {
            var heads = jdbc.queryForList(
                    "SELECT h.build_id FROM p_settlement_projection_head h JOIN p_settlement_projection_builds b ON b.id=h.build_id WHERE h.id=1 AND b.status='ACTIVE' AND b.verified_at IS NOT NULL",
                    UUID.class);
            UUID head = heads.isEmpty() ? null : heads.get(0);
            if ("RESOLVE".equals(action) && head != null && projectionHealthy(head, key)) {
                state = "DONE";
                explanation = "SUPERSEDED_BUILD_VERIFIED";
                resolve(incidentId);
                clearProjectionError(build, key);
            } else explanation = "INACTIVE_BUILD";
        } else if ("REPLAY".equals(action) && "B".equals(buildInfo.get("mode"))) {
            boolean inflight = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM p_financial_replay_commands WHERE incident_id=? AND status IN ('REQUESTED','PUBLISHED'))",
                    Boolean.class,
                    incidentId));
            if (inflight) explanation = "REPLAY_IN_FLIGHT";
            else {
                var fact = jdbc.queryForMap(
                        "SELECT payload,payment_id FROM p_settlement_projection_facts WHERE fact_key=?", key);
                payload = jdbc.queryForObject(
                        "SELECT (?::jsonb||jsonb_build_object('eventId',?::uuid::text,'replayCommandId',?::uuid::text))::text",
                        String.class,
                        fact.get("payload"),
                        commandId,
                        commandId);
                topic = (String) incident.get("source_topic");
                messageKey = fact.get("payment_id").toString();
                state = "REQUESTED";
                explanation = "REPLAY_REQUESTED";
                jdbc.update(
                        "UPDATE p_financial_consumer_incidents SET status='REPLAY_REQUESTED',updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') WHERE incident_id=?",
                        incidentId);
            }
        }
        jdbc.update(
                "INSERT INTO p_financial_replay_commands(command_id,incident_id,action,input_hash,actor,reason,status,explanation,target_topic,message_key,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                commandId,
                incidentId,
                action,
                hash,
                actor,
                reason,
                state,
                explanation,
                topic,
                messageKey,
                payload);
        return new Outcome(commandId, state, explanation);
    }
}
