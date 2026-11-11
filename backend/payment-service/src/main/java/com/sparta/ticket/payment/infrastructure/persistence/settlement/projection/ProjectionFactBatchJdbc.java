package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 초기 원천은 읽기 스냅샷과 분리해 작은 단위로 커밋한다. 금융 작업의 중복 키 대기를 제한한다. */
@Repository
@RequiredArgsConstructor
public class ProjectionFactBatchJdbc {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(List<Object[]> batch) {
        if (batch.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO p_settlement_projection_facts(fact_key,event_id,journal_id,payment_id,seller_id,schedule_id,
                  business_date,kind,amount,meaning_hash,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(fact_key) DO NOTHING
                """, batch);
        var expected = new java.util.HashMap<String, String>();
        for (Object[] row : batch) expected.put((String) row[0], (String) row[9]);
        String placeholders = String.join(",", java.util.Collections.nCopies(expected.size(), "?"));
        jdbc.query(
                "SELECT fact_key,meaning_hash FROM p_settlement_projection_facts WHERE fact_key IN (" + placeholders
                        + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    if (!rs.getString(2).equals(expected.remove(rs.getString(1))))
                        throw new IllegalStateException("보존한 원천과 원장 의미가 다르다");
                },
                expected.keySet().toArray());
        if (!expected.isEmpty()) throw new IllegalStateException("원천 보존이 누락됐다");
        batch.clear();
    }
}
