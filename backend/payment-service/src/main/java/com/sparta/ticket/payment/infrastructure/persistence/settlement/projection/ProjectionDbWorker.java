package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.payment.application.port.settlement.ProjectionBatchApplier;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** DB 전달과 집계/완료 기록을 한 TX로 묶는다. 네트워크 전송·임대 인수는 필요하지 않다. */
@Repository
@RequiredArgsConstructor
public class ProjectionDbWorker {
    private final JdbcTemplate jdbc;
    private final ProjectionBatchApplier applier;

    @Transactional
    public int process(UUID build, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("DB 집계 묶음은 1~1000건이다");
        // Note: 같은 세대의 판매/환불을 직렬화해 receipt가 다른 묶음의 delivery 잠금과 교착하지 않게 한다.
        var owner = jdbc.queryForList("""
                SELECT id FROM p_settlement_projection_builds WHERE id=? AND mode='D'
                  AND status IN ('BUILDING','VERIFYING','READY','ACTIVE') FOR UPDATE SKIP LOCKED
                """, build);
        if (owner.isEmpty()) return 0;
        var facts =
                jdbc.query("""
                SELECT f.payload FROM p_settlement_projection_deliveries d
                  JOIN p_settlement_projection_facts f ON f.fact_key=d.fact_key
                WHERE d.build_id=? AND d.published_at IS NULL
                ORDER BY d.fact_key LIMIT ? FOR UPDATE OF d SKIP LOCKED
                """, (rs, row) -> ProjectionJson.read(rs.getString(1), SettlementFact.class), build, limit);
        if (facts.isEmpty()) return 0;
        applier.apply(build, facts);
        // Note: D 모드의 published_at은 DB 전달 완료다. Kafka의 broker ACK를 뜻하지 않는다.
        jdbc.batchUpdate(
                """
                UPDATE p_settlement_projection_deliveries SET published_at=CURRENT_TIMESTAMP,
                  lease_token=NULL,leased_until=NULL,last_error=NULL
                WHERE build_id=? AND fact_key=? AND published_at IS NULL
                """,
                facts.stream()
                        .map(fact -> new Object[] {build, fact.sourceFactKey()})
                        .toList());
        return facts.size();
    }
}
