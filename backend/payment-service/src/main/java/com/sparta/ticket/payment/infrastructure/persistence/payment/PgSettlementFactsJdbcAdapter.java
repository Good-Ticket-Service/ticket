package com.sparta.ticket.payment.infrastructure.persistence.payment;

import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementFacts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PgSettlementFactsJdbcAdapter implements PgSettlementFacts {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT p.id,p.pg_transaction_id,p.order_id,p.status,
              (p.status='IN_PROGRESS' OR (p.status='FAILED' AND p.next_reconcile_at IS NOT NULL
                  AND coalesce(r.status,'')<>'DONE')) AS pending,
              CASE WHEN p.status='FAILED' AND r.reason='AUTO_VOID' AND r.status='DONE'
                THEN r.refund_amount ELSE coalesce(p.pg_approved_amount,p.amount) END AS amount,
              coalesce(p.pg_approved_at,p.approved_at) AS approved_at,
              r.refund_amount,CASE WHEN r.status='DONE' THEN r.refunded_at END AS refunded_at,
              EXISTS(SELECT 1 FROM p_ledger_transactions t WHERE t.source_type='SALE'
                AND t.action='POST' AND t.source_id=p.id) AS sale_posted,
              EXISTS(SELECT 1 FROM p_ledger_transactions t WHERE t.source_type='REFUND'
                AND t.source_id=r.id) AS refund_posted
            FROM p_payments p LEFT JOIN p_refunds r ON r.payment_id=p.id AND r.deleted_at IS NULL
            WHERE p.deleted_at IS NULL AND p.pg_provider=:provider
            """;

    @Override
    public Map<String, Facts> find(String provider, List<String> paymentKeys) {
        if (paymentKeys.isEmpty()) return Map.of();
        return jdbc
                .query(
                        SELECT + " AND p.pg_transaction_id IN (:keys)",
                        Map.of("provider", provider, "keys", paymentKeys),
                        this::map)
                .stream()
                .collect(Collectors.toMap(Facts::paymentKey, f -> f));
    }

    @Override
    public List<Facts> candidates(String provider, LocalDateTime from, LocalDateTime until, long after, int size) {
        return jdbc.query(
                SELECT + """
                 AND p.id>:after AND p.pg_transaction_id IS NOT NULL
                 AND ((coalesce(p.pg_approved_at,p.approved_at)>=:from AND coalesce(p.pg_approved_at,p.approved_at)<:until)
                   OR (r.status='DONE' AND r.refunded_at>=:from AND r.refunded_at<:until))
                 ORDER BY p.id LIMIT :size
                """,
                Map.of("provider", provider, "from", from, "until", until, "after", after, "size", size),
                this::map);
    }

    private Facts map(ResultSet rs, int row) throws SQLException {
        return new Facts(
                rs.getLong("id"),
                rs.getString("pg_transaction_id"),
                rs.getString("order_id"),
                rs.getString("status"),
                rs.getBoolean("pending"),
                rs.getLong("amount"),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("refund_amount") == null ? null : ((Number) rs.getObject("refund_amount")).longValue(),
                rs.getObject("refunded_at", LocalDateTime.class),
                rs.getBoolean("sale_posted"),
                rs.getBoolean("refund_posted"));
    }
}
