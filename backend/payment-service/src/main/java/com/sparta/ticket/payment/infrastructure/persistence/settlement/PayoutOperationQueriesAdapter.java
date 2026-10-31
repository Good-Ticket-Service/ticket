package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.PayoutOperationQueries;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 지급 운영 화면 읽기. 화면용이라 Entity 대신 필요한 열만 native SQL로 읽고, 볼 이유(attention)는 SQL에서 계산한다. */
@Repository
public class PayoutOperationQueriesAdapter implements PayoutOperationQueries {

    private static final String VIEW = """
            SELECT * FROM (
                SELECT id, statement_id, seller_id, amount, status,
                       CASE
                           WHEN status = 'READY' AND last_error = 'ACCOUNT_NOT_REGISTERED' THEN 'ACCOUNT_MISSING'
                           WHEN status = 'READY' AND last_error LIKE 'GATE%' THEN 'GATE_BLOCKED'
                           WHEN status = 'READY' AND next_attempt_at IS NULL THEN 'READY_UNSCHEDULED'
                           WHEN status = 'READY' THEN 'READY'
                           WHEN status = 'REQUESTED' AND inquiry_only THEN 'INQUIRY_ONLY'
                           WHEN status = 'REQUESTED' AND provider_status IN ('ACCEPTED', 'PROCESSING') THEN 'IN_PROGRESS'
                           WHEN status = 'REQUESTED' THEN 'RESULT_UNKNOWN'
                           ELSE status
                       END AS attention,
                       attempt, version, provider_status, inquiry_only, verification_reason, failure_code, last_error,
                       next_attempt_at, requested_at, done_at, bank_code, holder_masked, account_version
                FROM p_payouts) v
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<View> findPayouts(long after, int limit, String status, String attention) {
        boolean all = status == null && attention == null;
        return rows(entityManager
                        .createNativeQuery(VIEW
                                + " WHERE v.id > :after"
                                + " AND (CAST(:status AS VARCHAR) IS NULL OR v.status = CAST(:status AS VARCHAR))"
                                + " AND (CAST(:attention AS VARCHAR) IS NULL"
                                + " OR v.attention = CAST(:attention AS VARCHAR))"
                                + (all ? " AND v.status <> 'DONE'" : "")
                                + " ORDER BY v.id")
                        .setParameter("after", after)
                        .setParameter("status", status)
                        .setParameter("attention", attention)
                        .setMaxResults(limit)
                        .getResultList())
                .stream()
                .map(PayoutOperationQueriesAdapter::view)
                .toList();
    }

    @Override
    public Optional<View> findPayout(Long payoutId) {
        return rows(entityManager
                        .createNativeQuery(VIEW + " WHERE v.id = :id")
                        .setParameter("id", payoutId)
                        .getResultList())
                .stream()
                .findFirst()
                .map(PayoutOperationQueriesAdapter::view);
    }

    @Override
    public List<Receivable> findReceivables(long after, int limit) {
        return rows(entityManager
                        .createNativeQuery("SELECT seller_id, SUM(remaining), COUNT(*), MIN(created_at)"
                                + " FROM p_settlement_items WHERE kind = 'DEDUCTION' AND status = 'OPEN'"
                                + " AND seller_id > :after GROUP BY seller_id ORDER BY seller_id")
                        .setParameter("after", after)
                        .setMaxResults(limit)
                        .getResultList())
                .stream()
                .map(r -> new Receivable(
                        ((Number) r[0]).longValue(),
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).intValue(),
                        time(r[3])))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(List<?> rows) {
        return (List<Object[]>) rows;
    }

    private static View view(Object[] r) {
        return new View(
                ((Number) r[0]).longValue(),
                ((Number) r[1]).longValue(),
                ((Number) r[2]).longValue(),
                ((Number) r[3]).longValue(),
                (String) r[4],
                (String) r[5],
                ((Number) r[6]).intValue(),
                ((Number) r[7]).longValue(),
                (String) r[8],
                (Boolean) r[9],
                (String) r[10],
                (String) r[11],
                (String) r[12],
                time(r[13]),
                time(r[14]),
                time(r[15]),
                (String) r[16],
                (String) r[17],
                r[18] == null ? null : ((Number) r[18]).longValue());
    }

    private static LocalDateTime time(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime local) return local;
        return ((Timestamp) value).toLocalDateTime();
    }
}
