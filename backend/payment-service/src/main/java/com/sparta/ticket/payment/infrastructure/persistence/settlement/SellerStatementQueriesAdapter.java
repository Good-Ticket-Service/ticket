package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 정산서·명세·지급·항목을 묶어 읽는 판매자 조회. 화면용 읽기라 Entity 대신 필요한 열만 native SQL로 읽는다. */
@Repository
public class SellerStatementQueriesAdapter implements SellerStatementQueries {

    private static final String SUMMARY_SELECT = "SELECT s.id, s.schedule_id,"
            + " (SELECT MIN(i.schedule_start_at) FROM p_settlement_items i WHERE i.statement_id = s.id),"
            + " s.sequence, s.sale_total, s.offset_total, s.payable_amount, s.status, s.issued_at, s.paid_at, p.status"
            + " FROM p_settlement_statements s LEFT JOIN p_payouts p ON p.statement_id = s.id";

    private static final String RANGE_WHERE = " WHERE s.seller_id = :sellerId AND s.issued_at >= :from"
            + " AND s.issued_at < :to AND (CAST(:status AS VARCHAR) IS NULL OR s.status = CAST(:status AS VARCHAR))";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Summary> findStatements(
            Long sellerId, LocalDateTime from, LocalDateTime to, String status, int offset, int limit) {
        Query query = range(
                        entityManager.createNativeQuery(
                                SUMMARY_SELECT + RANGE_WHERE + " ORDER BY s.issued_at DESC, s.id DESC"),
                        sellerId,
                        from,
                        to,
                        status)
                .setFirstResult(offset)
                .setMaxResults(limit);
        return rows(query).stream().map(SellerStatementQueriesAdapter::summary).toList();
    }

    @Override
    public long countStatements(Long sellerId, LocalDateTime from, LocalDateTime to, String status) {
        Query query = range(
                entityManager.createNativeQuery("SELECT count(*) FROM p_settlement_statements s" + RANGE_WHERE),
                sellerId,
                from,
                to,
                status);
        return ((Number) query.getSingleResult()).longValue();
    }

    @Override
    public Optional<Summary> findStatement(Long sellerId, Long statementId) {
        Query query = entityManager
                .createNativeQuery(SUMMARY_SELECT + " WHERE s.id = :statementId AND s.seller_id = :sellerId")
                .setParameter("statementId", statementId)
                .setParameter("sellerId", sellerId);
        return rows(query).stream().findFirst().map(SellerStatementQueriesAdapter::summary);
    }

    @Override
    public List<Line> findLines(Long statementId) {
        Query query = entityManager
                .createNativeQuery("SELECT l.item_id, l.kind, i.payment_id, i.gross_amount, i.commission,"
                        + " i.commission_vat, i.seller_amount, l.amount FROM p_settlement_statement_lines l"
                        + " JOIN p_settlement_items i ON i.id = l.item_id WHERE l.statement_id = :statementId"
                        + " ORDER BY l.id")
                .setParameter("statementId", statementId);
        return rows(query).stream()
                .map(r -> new Line(
                        longValue(r[0]),
                        (String) r[1],
                        longValue(r[2]),
                        intValue(r[3]),
                        intValue(r[4]),
                        intValue(r[5]),
                        intValue(r[6]),
                        longValue(r[7])))
                .toList();
    }

    @Override
    public Receivable findReceivable(Long sellerId) {
        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT COALESCE(SUM(remaining), 0), MIN(created_at) FROM p_settlement_items"
                        + " WHERE seller_id = :sellerId AND kind = 'DEDUCTION' AND status = 'OPEN'"
                        + " AND deleted_at IS NULL")
                .setParameter("sellerId", sellerId)
                .getSingleResult();
        return new Receivable(longValue(row[0]), time(row[1]));
    }

    private static Query range(Query query, Long sellerId, LocalDateTime from, LocalDateTime to, String status) {
        return query.setParameter("sellerId", sellerId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("status", status);
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    private static Summary summary(Object[] r) {
        return new Summary(
                longValue(r[0]),
                longValue(r[1]),
                time(r[2]),
                intValue(r[3]),
                longValue(r[4]),
                longValue(r[5]),
                longValue(r[6]),
                (String) r[7],
                time(r[8]),
                time(r[9]),
                (String) r[10]);
    }

    private static Long longValue(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer intValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static LocalDateTime time(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime local) return local;
        return ((Timestamp) value).toLocalDateTime();
    }
}
