package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.payment.application.port.settlement.SettlementPreviewReader;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** A0 비교군. 같은 판매자 API/권한/RR 경계에서 원장을 직접 집계한다. 운영 bean으로 등록하지 않는다. */
final class ProjectionDirectPreview implements SettlementPreviewReader {
    private final JdbcTemplate jdbc;
    private final SettlementSourceJdbc source;

    ProjectionDirectPreview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.source = new SettlementSourceJdbc(jdbc);
    }

    @Override
    public Preview read(long seller, Long schedule, LocalDate from, LocalDate to, int page, int size) {
        String predicate = " AND t.seller_id=? AND t.occurred_at>=? AND t.occurred_at<?"
                + (schedule == null ? "" : " AND p.schedule_id=?");
        var args = new ArrayList<Object>(
                List.of(seller, from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        if (schedule != null) args.add(schedule);
        String selection = SettlementSourceJdbc.SOURCE + predicate;
        long count = jdbc.queryForObject(
                "SELECT count(DISTINCT (occurred_at::date,schedule_id)) FROM (" + selection + ") s",
                Long.class,
                args.toArray());
        var paged = new ArrayList<>(args);
        paged.add(size);
        paged.add(Math.multiplyExact(page, size));
        var days = jdbc.query(
                """
                SELECT occurred_at::date,schedule_id,count(*) FILTER(WHERE source_type='SALE'),
                  coalesce(sum(seller_amount) FILTER(WHERE source_type='SALE'),0),count(*) FILTER(WHERE source_type='REFUND'),
                  coalesce(sum(seller_amount) FILTER(WHERE source_type='REFUND'),0)
                FROM (%s) s GROUP BY occurred_at::date,schedule_id ORDER BY occurred_at::date DESC,schedule_id LIMIT ? OFFSET ?
                """.formatted(selection),
                (rs, n) -> new Day(
                        rs.getObject(1, LocalDate.class),
                        rs.getLong(2),
                        Totals.from(new SettlementTotals(rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)))),
                paged.toArray());
        long pending = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_jobs j JOIN p_payments p ON p.id=j.payment_id WHERE p.seller_id=? AND j.status<>'DONE'",
                Long.class,
                seller);
        long unposted = jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_items WHERE seller_id=? AND kind='SALE' AND status='UNPOSTED'",
                Long.class,
                seller);
        long refunds = jdbc.queryForObject(
                "SELECT count(*) FROM p_refunds r JOIN p_payments p ON p.id=r.payment_id WHERE p.seller_id=? AND r.status IN ('WAITING','REQUESTED','FAILED')",
                Long.class,
                seller);
        return new Preview(
                pending + unposted > 0 ? "STALE" : "READY",
                null,
                0,
                from,
                to,
                Totals.from(source.totals(seller, schedule, from, to)),
                new PageResponse<>(
                        days, page, size, count, (int) ((count + size - 1) / size), "businessDate,DESC;scheduleId,ASC"),
                new Health(0, 0, 0, pending, unposted, refunds, null, null));
    }
}
