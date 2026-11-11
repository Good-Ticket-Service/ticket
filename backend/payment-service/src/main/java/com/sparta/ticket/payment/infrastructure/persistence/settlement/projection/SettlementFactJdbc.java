package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.payment.application.port.settlement.SettlementFactJournal;
import com.sparta.ticket.payment.domain.model.settlement.LedgerKey;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원장 반영 TX 안에 불변 source/outbox를 함께 남긴다. Kafka와의 통신은 하지 않는다. */
@Repository
public class SettlementFactJdbc implements SettlementFactJournal {
    private final JdbcTemplate jdbc;
    private final SettlementSourceJdbc source;

    public SettlementFactJdbc(JdbcTemplate jdbc, SettlementSourceJdbc source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(LedgerKey key) {
        if (key.sourceType() != LedgerSourceType.SALE && key.sourceType() != LedgerSourceType.REFUND) return;
        List<SettlementFact> facts = source.facts(
                " AND t.source_type=? AND t.source_id=? AND t.action=?",
                key.sourceType().name(),
                key.sourceId(),
                key.action().name());
        if (facts.size() != 1) throw new IllegalStateException("집계 원천의 고정 메타데이터를 확인할 수 없다");
        SettlementFact fact = facts.get(0);
        // Note: 기능 도입 전의 판매가 환불돼도 원 판매를 복원한 다음 환불 사실을 기록한다.
        if (fact.kind() == SettlementFact.Kind.REFUND) {
            var sales = source.facts(" AND t.source_type='SALE' AND t.source_id=?", fact.paymentId());
            if (sales.size() != 1) throw new IllegalStateException("완료 환불의 원 판매가 없다");
            save(sales.get(0));
        }
        save(fact);
    }

    public void save(SettlementFact sourceFact) {
        var eventId = UUID.nameUUIDFromBytes(sourceFact.sourceFactKey().getBytes(StandardCharsets.UTF_8));
        var fact = new SettlementFact(
                eventId.toString(),
                sourceFact.sourceFactKey(),
                sourceFact.kind(),
                sourceFact.action(),
                sourceFact.paymentId(),
                sourceFact.refundId(),
                sourceFact.sellerId(),
                sourceFact.scheduleId(),
                sourceFact.occurredAt(),
                sourceFact.sellerAmount(),
                sourceFact.policyVersion());
        int inserted = jdbc.update(
                """
                INSERT INTO p_settlement_projection_facts(fact_key,event_id,journal_id,payment_id,seller_id,schedule_id,
                  business_date,kind,amount,meaning_hash,payload)
                SELECT ?,?,id,?,?,?,?,?,?,?,? FROM p_ledger_transactions
                WHERE source_type=? AND source_id=? AND action=? ON CONFLICT(fact_key) DO NOTHING
                """,
                fact.sourceFactKey(),
                eventId,
                fact.paymentId(),
                fact.sellerId(),
                fact.scheduleId(),
                fact.businessDate(),
                fact.kind().name(),
                fact.sellerAmount(),
                fact.meaningHash(),
                ProjectionJson.write(fact),
                fact.kind().name(),
                fact.kind() == SettlementFact.Kind.SALE ? fact.paymentId() : fact.refundId(),
                fact.action());
        if (inserted == 0) {
            String hash = jdbc.queryForObject(
                    "SELECT meaning_hash FROM p_settlement_projection_facts WHERE fact_key=?",
                    String.class,
                    fact.sourceFactKey());
            if (!fact.meaningHash().equals(hash)) throw new IllegalStateException("집계 source 업무 의미가 충돌한다");
        }
    }
}
