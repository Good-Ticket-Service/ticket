package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.List;

/** 성능 시험의 고정 10,000원 판매 분개. 운영과 같은 factory의 균형 검증을 통과한다. */
public final class LedgerMeasurementFixture {
    private LedgerMeasurementFixture() {}

    public static LedgerTransaction sale(long paymentId) {
        return LedgerTransaction.create(
                new LedgerKey(LedgerSourceType.SALE, paymentId, LedgerAction.POST),
                1 + paymentId % 1000,
                paymentId,
                null,
                null,
                null,
                "measure",
                null,
                LocalDateTime.of(2026, 10, 19, 10, 0),
                List.of(
                        LedgerLine.debit(LedgerAccount.PG_CLEARING, 10000),
                        LedgerLine.credit(LedgerAccount.SELLER_PAYABLE, 8680),
                        LedgerLine.credit(LedgerAccount.COMMISSION_REVENUE, 1200),
                        LedgerLine.credit(LedgerAccount.VAT_PAYABLE, 120)));
    }
}
