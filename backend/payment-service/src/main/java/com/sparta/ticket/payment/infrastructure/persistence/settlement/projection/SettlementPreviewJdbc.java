package com.sparta.ticket.payment.infrastructure.persistence.settlement.projection;

import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.payment.application.port.settlement.SettlementPreviewReader;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import com.sparta.ticket.payment.infrastructure.settlement.projection.ProjectionProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** head·합계·상세·health는 호출자의 동일 RR snapshot에서 읽는다. */
@Repository
@RequiredArgsConstructor
public class SettlementPreviewJdbc implements SettlementPreviewReader {
    private final JdbcTemplate jdbc;
    private final ProjectionProperties config;

    @Override
    public Preview read(long seller, Long schedule, LocalDate from, LocalDate to, int page, int size) {
        var head = jdbc.queryForMap(
                "SELECT h.generation,h.build_id,b.last_fanout_at,b.verified_at,b.fanout_snapshot FROM p_settlement_projection_head h LEFT JOIN p_settlement_projection_builds b ON b.id=h.build_id WHERE h.id=1");
        UUID build = (UUID) head.get("build_id");
        long generation = ((Number) head.get("generation")).longValue();
        if (build == null)
            return new Preview(
                    "INITIALIZING",
                    null,
                    generation,
                    from,
                    to,
                    null,
                    new PageResponse<>(List.of(), page, size, 0, 0, "businessDate,DESC;scheduleId,ASC"),
                    null);
        String filter = " WHERE build_id=? AND seller_id=? AND business_date BETWEEN ? AND ?"
                + (schedule == null ? "" : " AND schedule_id=?");
        var args = new ArrayList<Object>(List.of(build, seller, from, to));
        if (schedule != null) args.add(schedule);
        var total = jdbc.queryForObject(
                "SELECT coalesce(sum(sale_count),0),coalesce(sum(sale_amount),0),coalesce(sum(refund_count),0),coalesce(sum(refund_amount),0) FROM p_settlement_projection_totals"
                        + filter,
                (rs, n) ->
                        Totals.from(new SettlementTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))),
                args.toArray());
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_totals" + filter, Long.class, args.toArray());
        var paged = new ArrayList<>(args);
        paged.add(size);
        paged.add(Math.multiplyExact(page, size));
        var days = jdbc.query(
                "SELECT business_date,schedule_id,sale_count,sale_amount,refund_count,refund_amount FROM p_settlement_projection_totals"
                        + filter + " ORDER BY business_date DESC,schedule_id LIMIT ? OFFSET ?",
                (rs, n) -> new Day(
                        rs.getObject(1, LocalDate.class),
                        rs.getLong(2),
                        Totals.from(new SettlementTotals(rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)))),
                paged.toArray());
        String factFilter =
                " f.seller_id=? AND f.business_date BETWEEN ? AND ?" + (schedule == null ? "" : " AND f.schedule_id=?");
        var factArgs = new ArrayList<Object>(List.of(seller, from, to));
        if (schedule != null) factArgs.add(schedule);
        var buildArgs = new ArrayList<Object>();
        buildArgs.add(build);
        buildArgs.addAll(factArgs);
        long undelivered = undelivered(build, (String) head.get("fanout_snapshot"), factFilter, factArgs);
        long unapplied = Math.addExact(
                undelivered,
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_deliveries" + filter + " AND NOT applied",
                        Long.class,
                        args.toArray()));
        long unpublished = Math.addExact(
                undelivered,
                jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_deliveries" + filter
                                + " AND published_at IS NULL",
                        Long.class,
                        args.toArray()));
        long errors = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_projection_errors e JOIN p_settlement_projection_facts f ON f.fact_key=e.fact_key WHERE e.build_id=? AND NOT e.resolved AND "
                        + factFilter,
                Long.class,
                buildArgs.toArray());
        // Note: 아직 귀속 날짜가 확정되지 않은 위험은 판매자 전체 범위로 표시한다.
        long originPending = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_jobs j JOIN p_payments p ON p.id=j.payment_id WHERE p.seller_id=? AND j.status<>'DONE'",
                Long.class,
                seller);
        long unposted = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_items WHERE seller_id=? AND kind='SALE' AND status='UNPOSTED'",
                Long.class,
                seller);
        long pendingRefunds = jdbc.queryForObject(
                "SELECT count(*) FROM p_refunds r JOIN p_payments p ON p.id=r.payment_id WHERE p.seller_id=? AND r.status IN ('WAITING','REQUESTED','FAILED')",
                Long.class,
                seller);
        OffsetDateTime last = timestamp(head.get("last_fanout_at"));
        OffsetDateTime verified = timestamp(head.get("verified_at"));
        boolean globalError = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM p_settlement_projection_errors WHERE build_id=? AND NOT resolved)",
                Boolean.class,
                build);
        OffsetDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
        boolean stale = last == null
                || last.toInstant().plus(config.getStaleAfter()).isBefore(now.toInstant())
                || unapplied + unpublished + errors + originPending + unposted > 0
                || globalError;
        return new Preview(
                stale ? "STALE" : "READY",
                build,
                generation,
                from,
                to,
                total,
                new PageResponse<>(
                        days, page, size, count, (int) ((count + size - 1) / size), "businessDate,DESC;scheduleId,ASC"),
                new Health(unpublished, unapplied, errors, originPending, unposted, pendingRefunds, last, verified));
    }

    /** fanout 이전의 새 사실도 세되 오래된 커밋 전체를 매 요청마다 대조하지 않는다. */
    private long undelivered(UUID build, String snapshot, String filter, List<Object> scope) {
        var values = new ArrayList<Object>();
        String selection;
        if (snapshot == null) {
            // Note: snapshot이 없는 비정상/이행 상태도 미반영을 정상 0건으로 숨기지 않는다.
            selection = "SELECT fact_key,seller_id,schedule_id,business_date FROM p_settlement_projection_facts";
        } else {
            selection = "SELECT fact_key,seller_id,schedule_id,business_date FROM p_settlement_projection_facts "
                    + "WHERE writer_xid>=pg_snapshot_xmin(?::pg_snapshot) "
                    + "AND NOT pg_visible_in_snapshot(writer_xid,?::pg_snapshot)";
            values.add(snapshot);
            values.add(snapshot);
        }
        values.addAll(scope);
        values.add(build);
        return jdbc.queryForObject(
                "WITH recent AS MATERIALIZED (" + selection + ") SELECT count(*) FROM recent f WHERE " + filter
                        + " AND NOT EXISTS(SELECT 1 FROM p_settlement_projection_deliveries d "
                        + "WHERE d.build_id=? AND d.fact_key=f.fact_key)",
                Long.class,
                values.toArray());
    }

    private static OffsetDateTime timestamp(Object value) {
        if (value == null) return null;
        java.time.Instant instant = value instanceof java.sql.Timestamp timestamp
                ? timestamp.toInstant()
                : ((OffsetDateTime) value).toInstant();
        return instant.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }
}
