package com.sparta.ticket.payment.domain.model.pgsettlement;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** PG 명세 원문. 승인과 전액 취소는 서로 다른 사건이며 취소 금액·수수료는 음수다. */
public final class PgSettlement {
    private PgSettlement() {}

    public record Line(
            String lineKey,
            String paymentKey,
            String orderId,
            String type,
            long amount,
            long fee,
            LocalDateTime occurredAt) {
        public Line {
            if (lineKey == null
                    || lineKey.isBlank()
                    || paymentKey == null
                    || paymentKey.isBlank()
                    || orderId == null
                    || orderId.isBlank()
                    || occurredAt == null) throw new IllegalArgumentException("명세 거래의 식별자·시각이 없다");
            if (!("APPROVAL".equals(type) && amount > 0 && fee >= 0 || "CANCEL".equals(type) && amount < 0 && fee <= 0)
                    || Math.abs(fee) > Math.abs(amount)) throw new IllegalArgumentException("명세 거래의 유형·금액·수수료가 맞지 않는다");
            if (!lineKey.equals(paymentKey + ":" + type)) throw new IllegalArgumentException("전액 취소 명세의 사건 키가 맞지 않는다");
        }
    }

    public record Totals(long count, long amount, long fee, long carryIn, long payout, long carryOut) {
        public Totals {
            if (count < 0
                    || carryIn < 0
                    || payout < 0
                    || carryOut < 0
                    || payout > 0 && carryOut > 0
                    || Math.addExact(Math.subtractExact(Math.subtractExact(amount, fee), carryIn), carryOut) != payout)
                throw new IllegalArgumentException("명세 합계의 보존식이 맞지 않는다");
        }
    }

    public record Page(
            String provider,
            LocalDate settlementDate,
            String snapshotId,
            Totals totals,
            List<Line> lines,
            String nextCursor) {
        public Page {
            if (provider == null
                    || settlementDate == null
                    || snapshotId == null
                    || snapshotId.isBlank()
                    || totals == null
                    || lines == null) throw new IllegalArgumentException("명세 머리말이 없다");
            lines = List.copyOf(lines);
        }
    }

    public enum Finding {
        MATCHED,
        PENDING,
        ONLY_IN_PG,
        ONLY_IN_OURS,
        STATUS_MISMATCH,
        AMOUNT_MISMATCH,
        FEE_MISMATCH,
        DATE_MISMATCH
    }

    /** 결제 소유 조회 포트가 넘기는 사실. 정산은 결제 Entity를 참조하지 않는다. */
    public record Expected(
            String paymentKey,
            String orderId,
            String type,
            long amount,
            LocalDateTime occurredAt,
            boolean pending,
            boolean ledgerPosted,
            String paymentStatus) {
        public String lineKey() {
            return paymentKey + ":" + type;
        }
    }

    public record Decision(
            Finding finding,
            Long expectedAmount,
            Long expectedFee,
            LocalDate expectedDate,
            String policyVersion,
            long clearingAmount) {}
}
