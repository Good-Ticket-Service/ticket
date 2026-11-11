package com.sparta.ticket.payment.application.port.settlement;

import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementTotals;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface SettlementPreviewReader {
    record Totals(
            long saleCount, long saleSellerAmount, long refundCount, long refundSellerAmount, long netSellerAmount) {
        public static Totals from(SettlementTotals value) {
            return new Totals(
                    value.saleCount(),
                    value.saleSellerAmount(),
                    value.refundCount(),
                    value.refundSellerAmount(),
                    value.netSellerAmount());
        }
    }

    record Day(LocalDate businessDate, long scheduleId, Totals totals) {}

    record Health(
            long unpublished,
            long unapplied,
            long errors,
            long originPending,
            long unposted,
            long pendingRefunds,
            OffsetDateTime lastFanoutAt,
            OffsetDateTime verifiedAt) {}

    record Preview(
            String status,
            UUID buildId,
            long generation,
            LocalDate from,
            LocalDate to,
            Totals totals,
            PageResponse<Day> days,
            Health health) {}

    Preview read(long sellerId, Long scheduleId, LocalDate from, LocalDate to, int page, int size);
}
