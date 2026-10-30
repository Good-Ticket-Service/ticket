package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 업무별 분개 생성 규칙(settlement-design 4.5절). DB를 바꾸지 않는 순수 함수다.
 * 금액은 SALE에 고정한 값만 쓰고 현재 요율로 다시 계산하지 않는다. 0원 줄(P=0, Bf=0 등)은 만들지 않는다.
 */
public final class LedgerJournals {

    private LedgerJournals() {}

    /** 판매 승인. 차변 PG_CLEARING E·DISCOUNT_EXPENSE P, 대변 SELLER_PAYABLE N·COMMISSION_REVENUE F·BOOKING_FEE_REVENUE Bn·VAT_PAYABLE V+Bv. */
    public static LedgerTransaction sale(SettlementItem sale, LocalDateTime occurredAt) {
        return LedgerTransaction.create(
                new LedgerKey(LedgerSourceType.SALE, sale.getPaymentId(), LedgerAction.POST),
                sale.getSellerId(),
                sale.getPaymentId(),
                null,
                null,
                null,
                sale.getPolicyVersion(),
                null,
                occurredAt,
                saleLines(sale, LedgerAccount.SELLER_PAYABLE, false));
    }

    /** 정산서 발행 전 환불. 저장된 판매 분개의 줄을 그대로 뒤집는다. */
    public static LedgerTransaction reverse(LedgerTransaction original, long refundId, LocalDateTime occurredAt) {
        if (original.getId() == null
                || original.getSourceType() != LedgerSourceType.SALE
                || original.getAction() != LedgerAction.POST) throw new IllegalArgumentException("저장된 판매 분개만 반대로 뒤집는다");
        return LedgerTransaction.create(
                new LedgerKey(LedgerSourceType.REFUND, refundId, LedgerAction.REVERSE),
                original.getSellerId(),
                original.getPaymentId(),
                refundId,
                null,
                null,
                original.getPolicyVersion(),
                original.getId(),
                occurredAt,
                original.lines().stream().map(LedgerLine::flipped).toList());
    }

    /**
     * 정산서 발행 뒤 환불. 판매자에게 이미 줬거나 줄 N 전액을 SELLER_RECEIVABLE로 돌려받을 채권으로 잡는다.
     * 판매 분개와 반대 방향이지만 SELLER_PAYABLE은 건드리지 않는다(발행한 정산서는 고정이다).
     */
    public static LedgerTransaction receivable(SettlementItem sale, long refundId, LocalDateTime occurredAt) {
        return LedgerTransaction.create(
                new LedgerKey(LedgerSourceType.REFUND, refundId, LedgerAction.RECEIVABLE),
                sale.getSellerId(),
                sale.getPaymentId(),
                refundId,
                null,
                null,
                sale.getPolicyVersion(),
                null,
                occurredAt,
                saleLines(sale, LedgerAccount.SELLER_RECEIVABLE, true));
    }

    /** 정산서 상계. 지급할 돈(SELLER_PAYABLE)에서 돌려받을 돈(SELLER_RECEIVABLE)을 뺀다. */
    public static LedgerTransaction offset(long statementId, long sellerId, long amount, LocalDateTime occurredAt) {
        return LedgerTransaction.create(
                new LedgerKey(LedgerSourceType.STATEMENT, statementId, LedgerAction.OFFSET),
                sellerId,
                null,
                null,
                statementId,
                null,
                null,
                null,
                occurredAt,
                List.of(
                        LedgerLine.debit(LedgerAccount.SELLER_PAYABLE, positive(amount)),
                        LedgerLine.credit(LedgerAccount.SELLER_RECEIVABLE, amount)));
    }

    /** 지급 확정. 모의 이체가 DONE일 때 한 번만 기록한다. */
    public static LedgerTransaction payoutDone(
            long payoutId, long statementId, long sellerId, long amount, LocalDateTime occurredAt) {
        return LedgerTransaction.create(
                new LedgerKey(LedgerSourceType.PAYOUT, payoutId, LedgerAction.DONE),
                sellerId,
                null,
                null,
                statementId,
                payoutId,
                null,
                null,
                occurredAt,
                List.of(
                        LedgerLine.debit(LedgerAccount.SELLER_PAYABLE, positive(amount)),
                        LedgerLine.credit(LedgerAccount.PAYOUT_CLEARING, amount)));
    }

    /** 판매 분개 줄. flip이면 차변·대변을 바꾼다. 판매자 몫 계정만 sellerAccount로 둔다. */
    private static List<LedgerLine> saleLines(SettlementItem sale, LedgerAccount sellerAccount, boolean flip) {
        if (sale.getKind() != SettlementItemKind.SALE) throw new IllegalArgumentException("SALE 항목만 분개한다");
        SaleAmounts a = sale.amounts();
        List<LedgerLine> lines = new ArrayList<>();
        add(lines, LedgerAccount.PG_CLEARING, a.paid(), !flip);
        add(lines, LedgerAccount.DISCOUNT_EXPENSE, a.discount(), !flip);
        add(lines, sellerAccount, a.sellerAmount(), flip);
        add(lines, LedgerAccount.COMMISSION_REVENUE, a.commission(), flip);
        add(lines, LedgerAccount.BOOKING_FEE_REVENUE, a.bookingFeeNet(), flip);
        add(lines, LedgerAccount.VAT_PAYABLE, (long) a.commissionVat() + a.bookingFeeVat(), flip);
        return lines;
    }

    private static void add(List<LedgerLine> lines, LedgerAccount account, long amount, boolean debit) {
        if (amount == 0) return;
        lines.add(debit ? LedgerLine.debit(account, amount) : LedgerLine.credit(account, amount));
    }

    private static long positive(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("상계·지급 금액은 0보다 커야 한다");
        return amount;
    }
}
