package com.sparta.ticket.payment.domain.model.settlement;

import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.BOOKING_FEE_REVENUE;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.COMMISSION_REVENUE;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.DISCOUNT_EXPENSE;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.PAYOUT_CLEARING;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.PG_CLEARING;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.SELLER_PAYABLE;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.SELLER_RECEIVABLE;
import static com.sparta.ticket.payment.domain.model.settlement.LedgerAccount.VAT_PAYABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 분개 생성 규칙(settlement-design 4.5절, 시험 S1). DB 없이 줄 구성과 균형만 본다. */
class LedgerJournalsTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final long PAYMENT_ID = 101L;
    private static final long SELLER_ID = 7L;
    private static final SettlementPolicy V1 =
            new SettlementPolicy("v1", AT.minusYears(1), 1200, 1000, 340, 1000, true, 3);
    private static final SettlementPolicy V2 =
            new SettlementPolicy("v2", AT.minusYears(1), 1000, 1000, 340, 1000, true, 3);
    private static final BusinessCalendar CALENDAR =
            new BusinessCalendar("cal-1", AT.minusYears(1), LocalDate.of(2026, 12, 31), Set.of());

    private static SettlementItem sale(int gross, int discount, int bookingFee, SettlementPolicy policy) {
        int paid = gross - discount + bookingFee;
        SaleSource source =
                new SaleSource(PAYMENT_ID, SELLER_ID, 3L, 4L, AT.plusDays(2), AT, gross, discount, bookingFee, paid);
        SaleAmounts amounts =
                ((SaleAmounts.Calculated) SaleAmounts.calculate(gross, discount, bookingFee, paid, policy)).amounts();
        return SettlementItem.postedSale(source, amounts, policy, CALENDAR, AT.plusDays(5), null);
    }

    private static LedgerTransaction saved(LedgerTransaction tx, long id) {
        ReflectionTestUtils.setField(tx, "id", id);
        return tx;
    }

    @Test
    void saleWithoutDiscountHasFourBalancedLines() {
        LedgerTransaction tx = LedgerJournals.sale(sale(10_000, 0, 0, V1), AT);

        assertThat(tx.key()).isEqualTo(new LedgerKey(LedgerSourceType.SALE, PAYMENT_ID, LedgerAction.POST));
        assertThat(tx.lines())
                .containsExactly(
                        LedgerLine.debit(PG_CLEARING, 10_000),
                        LedgerLine.credit(SELLER_PAYABLE, 8_680),
                        LedgerLine.credit(COMMISSION_REVENUE, 1_200),
                        LedgerLine.credit(VAT_PAYABLE, 120));
        assertThat(tx.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(tx.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(tx.getPolicyVersion()).isEqualTo("v1");
    }

    /** 예매수수료는 미적용 결정이지만 계산 경로는 남아 있으므로, 0이 아닐 때 줄이 빠지지 않는지 한 번 본다. */
    @Test
    void saleWithDiscountAndBookingFeeHasSixBalancedLines() {
        LedgerTransaction tx = LedgerJournals.sale(sale(10_000, 1_000, 1_000, V1), AT);

        assertThat(tx.lines())
                .containsExactly(
                        LedgerLine.debit(PG_CLEARING, 10_000),
                        LedgerLine.debit(DISCOUNT_EXPENSE, 1_000),
                        LedgerLine.credit(SELLER_PAYABLE, 8_680),
                        LedgerLine.credit(COMMISSION_REVENUE, 1_200),
                        LedgerLine.credit(BOOKING_FEE_REVENUE, 909),
                        LedgerLine.credit(VAT_PAYABLE, 211));
    }

    @Test
    void reverseFlipsOriginalLinesWithoutRecalculating() {
        LedgerTransaction original = saved(LedgerJournals.sale(sale(10_000, 1_000, 0, V1), AT), 1L);

        LedgerTransaction tx = LedgerJournals.reverse(original, 55L, AT.plusDays(1));

        assertThat(tx.key()).isEqualTo(new LedgerKey(LedgerSourceType.REFUND, 55L, LedgerAction.REVERSE));
        assertThat(tx.lines())
                .containsExactly(
                        LedgerLine.debit(SELLER_PAYABLE, 8_680),
                        LedgerLine.debit(COMMISSION_REVENUE, 1_200),
                        LedgerLine.debit(VAT_PAYABLE, 120),
                        LedgerLine.credit(PG_CLEARING, 9_000),
                        LedgerLine.credit(DISCOUNT_EXPENSE, 1_000));
        assertThat(tx.getReversesTransactionId()).isEqualTo(1L);
        assertThat(tx.getRefundId()).isEqualTo(55L);
        assertThat(tx.getPolicyVersion()).isEqualTo("v1");
    }

    @Test
    void reverseRejectsUnsavedOrNonSaleOriginal() {
        LedgerTransaction unsaved = LedgerJournals.sale(sale(10_000, 0, 0, V1), AT);
        LedgerTransaction offset = saved(LedgerJournals.offset(9L, SELLER_ID, 100, AT), 2L);
        LedgerTransaction reversal = saved(LedgerJournals.reverse(saved(unsaved, 1L), 55L, AT), 3L);

        assertThatThrownBy(() -> LedgerJournals.reverse(LedgerJournals.sale(sale(10_000, 0, 0, V1), AT), 55L, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerJournals.reverse(offset, 55L, AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerJournals.reverse(reversal, 56L, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receivableUsesFixedSaleAmounts() {
        LedgerTransaction tx = LedgerJournals.receivable(sale(10_000, 0, 0, V1), 55L, AT);

        assertThat(tx.key()).isEqualTo(new LedgerKey(LedgerSourceType.REFUND, 55L, LedgerAction.RECEIVABLE));
        assertThat(tx.lines())
                .containsExactly(
                        LedgerLine.debit(SELLER_RECEIVABLE, 8_680),
                        LedgerLine.debit(COMMISSION_REVENUE, 1_200),
                        LedgerLine.debit(VAT_PAYABLE, 120),
                        LedgerLine.credit(PG_CLEARING, 10_000));
        assertThat(tx.getReversesTransactionId()).isNull();
    }

    @Test
    void unpostedSaleCannotBeJournaled() {
        SettlementItem unposted = SettlementItem.unpostedSale(
                new SaleSource(PAYMENT_ID, SELLER_ID, 3L, 4L, null, AT, 10_000, 0, 0, 9_000),
                SettlementFailure.PAID_AMOUNT_MISMATCH);

        assertThatThrownBy(() -> LedgerJournals.sale(unposted, AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LedgerJournals.receivable(unposted, 55L, AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void offsetAndPayoutMoveSellerPayable() {
        assertThat(LedgerJournals.offset(9L, SELLER_ID, 500, AT).lines())
                .containsExactly(LedgerLine.debit(SELLER_PAYABLE, 500), LedgerLine.credit(SELLER_RECEIVABLE, 500));
        LedgerTransaction payout = LedgerJournals.payoutDone(3L, 9L, SELLER_ID, 700, AT);
        assertThat(payout.key()).isEqualTo(new LedgerKey(LedgerSourceType.PAYOUT, 3L, LedgerAction.DONE));
        assertThat(payout.getStatementId()).isEqualTo(9L);
        assertThat(payout.lines())
                .containsExactly(LedgerLine.debit(SELLER_PAYABLE, 700), LedgerLine.credit(PAYOUT_CLEARING, 700));
    }

    @Test
    void zeroOrNegativeAmountsAreRejected() {
        assertThatThrownBy(() -> LedgerJournals.offset(9L, SELLER_ID, 0, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerJournals.payoutDone(3L, 9L, SELLER_ID, -1, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerLine(PG_CLEARING, 100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerLine(PG_CLEARING, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameMeaningIgnoresOccurredAtButNotAmounts() {
        LedgerTransaction first = LedgerJournals.sale(sale(10_000, 0, 0, V1), AT);

        assertThat(first.sameMeaningAs(LedgerJournals.sale(sale(10_000, 0, 0, V1), AT.plusHours(1))))
                .isTrue();
        assertThat(first.sameMeaningAs(LedgerJournals.sale(sale(10_000, 0, 0, V2), AT)))
                .isFalse();
    }
}
