package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 불변 원장으로부터 조회 기여를 추출한다. 실제 원천과 파생 결과의 독립 SQL 대조에도 사용한다. */
@Repository
@RequiredArgsConstructor
public class SettlementSourceJdbc {
    private final JdbcTemplate jdbc;

    public static final String SOURCE = """
            SELECT t.id AS journal_id,t.writer_xid::text AS writer_xid,
              'v1:'||t.source_type||':'||t.source_id||':'||t.action AS fact_key,
              t.source_type,t.action,t.payment_id,t.refund_id,t.seller_id,p.schedule_id,
              t.occurred_at,t.policy_version,
              coalesce(a.amount,0)::bigint AS seller_amount
            FROM p_ledger_transactions t JOIN p_payments p ON p.id=t.payment_id AND p.seller_id=t.seller_id
            CROSS JOIN LATERAL (
              SELECT sum(CASE WHEN t.source_type='SALE' THEN e.credit-e.debit ELSE e.debit-e.credit END) AS amount
              FROM p_ledger_entries e WHERE e.transaction_id=t.id AND e.account=
                CASE WHEN t.action='RECEIVABLE' THEN 'SELLER_RECEIVABLE' ELSE 'SELLER_PAYABLE' END
            ) a
            WHERE ((t.source_type='SALE' AND t.action='POST') OR
              (t.source_type='REFUND' AND t.action IN ('REVERSE','RECEIVABLE')))
            """;

    public static final String TOTALS = """
            SELECT count(*) FILTER(WHERE source_type='SALE') AS sale_count,
              coalesce(sum(seller_amount) FILTER(WHERE source_type='SALE'),0)::bigint AS sale_amount,
              count(*) FILTER(WHERE source_type='REFUND') AS refund_count,
              coalesce(sum(seller_amount) FILTER(WHERE source_type='REFUND'),0)::bigint AS refund_amount
            FROM (%s) source
            """;

    public SettlementTotals totals(long sellerId, Long scheduleId, LocalDate from, LocalDate to) {
        String predicate = " AND t.seller_id=? AND t.occurred_at>=? AND t.occurred_at<?"
                + (scheduleId == null ? "" : " AND p.schedule_id=?");
        Object[] arguments = scheduleId == null
                ? new Object[] {sellerId, from.atStartOfDay(), to.plusDays(1).atStartOfDay()}
                : new Object[] {sellerId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), scheduleId};
        return jdbc.queryForObject(
                TOTALS.formatted(SOURCE + predicate),
                (rs, row) -> new SettlementTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                arguments);
    }

    public List<SettlementFact> facts(String predicate, Object... arguments) {
        return jdbc.query(
                SOURCE + predicate + " ORDER BY t.id",
                (rs, row) -> new SettlementFact(
                        "journal:" + rs.getLong("journal_id"),
                        rs.getString("fact_key"),
                        SettlementFact.Kind.valueOf(rs.getString("source_type")),
                        rs.getString("action"),
                        rs.getLong("payment_id"),
                        rs.getObject("refund_id", Long.class),
                        rs.getLong("seller_id"),
                        rs.getLong("schedule_id"),
                        rs.getTimestamp("occurred_at").toLocalDateTime(),
                        rs.getLong("seller_amount"),
                        rs.getString("policy_version")),
                arguments);
    }
}
