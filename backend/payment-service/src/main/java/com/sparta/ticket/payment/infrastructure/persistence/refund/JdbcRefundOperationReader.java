package com.sparta.ticket.payment.infrastructure.persistence.refund;

import com.sparta.ticket.payment.application.port.payment.RefundOperationReader;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcRefundOperationReader implements RefundOperationReader {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<Long> paymentIds(
            long afterRefundId,
            int limit,
            RefundStatus status,
            RefundRecoveryMode mode,
            PgProvider provider,
            LocalDateTime createdBefore) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.payment_id FROM p_refunds r JOIN p_payments p ON p.id = r.payment_id
                WHERE r.deleted_at IS NULL AND p.deleted_at IS NULL
                  AND r.status IN ('WAITING','REQUESTED','FAILED') AND r.id > :after
                """);
        var args = new HashMap<String, Object>();
        args.put("after", afterRefundId);
        args.put("limit", limit);
        if (status != null) {
            sql.append(" AND r.status = :status");
            args.put("status", status.name());
        }
        if (mode != null) {
            sql.append(" AND r.recovery_mode = :mode");
            args.put("mode", mode.name());
        }
        if (provider != null) {
            sql.append(" AND p.pg_provider = :provider");
            args.put("provider", provider.name());
        }
        if (createdBefore != null) {
            sql.append(" AND r.created_at <= :before");
            args.put("before", createdBefore);
        }
        sql.append(" ORDER BY r.id LIMIT :limit");
        return jdbc.queryForList(sql.toString(), args, Long.class);
    }
}
