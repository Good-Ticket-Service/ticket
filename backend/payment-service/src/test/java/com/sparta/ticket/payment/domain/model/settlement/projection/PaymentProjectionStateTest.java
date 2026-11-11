package com.sparta.ticket.payment.domain.model.settlement.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PaymentProjectionStateTest {
    static SettlementFact sale() {
        return new SettlementFact(
                "event-sale",
                "v1:SALE:1:POST",
                SettlementFact.Kind.SALE,
                "POST",
                1,
                null,
                10,
                20,
                LocalDateTime.of(2026, 11, 10, 23, 59),
                8000,
                "fixed");
    }

    static SettlementFact refund(String action, long amount) {
        return new SettlementFact(
                "event-refund",
                "v1:REFUND:2:" + action,
                SettlementFact.Kind.REFUND,
                action,
                1,
                2L,
                10,
                20,
                LocalDateTime.of(2026, 11, 11, 0, 1),
                amount,
                "fixed");
    }

    @Test
    void lateRefundUsesItsOwnDayAndOriginalFixedAmountForBothAccountingActions() {
        for (String action : java.util.List.of("REVERSE", "RECEIVABLE")) {
            var sale = PaymentProjectionState.empty().accept(sale());
            var refund = sale.state().accept(refund(action, 8000));
            assertThat(refund.error()).isNull();
            assertThat(refund.contributions()).hasSize(1);
            assertThat(refund.contributions().get(0).businessDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 11));
            assertThat(SettlementTotals.empty()
                            .add(sale())
                            .add(refund(action, 8000))
                            .netSellerAmount())
                    .isZero();
        }
    }

    @Test
    void refundBeforeSaleWaitsAndThenEmitsBothExactlyOnce() {
        var pending = PaymentProjectionState.empty().accept(refund("REVERSE", 8000));
        assertThat(pending.state().pending()).isTrue();
        assertThat(pending.contributions()).isEmpty();
        var completed = pending.state().accept(sale());
        assertThat(completed.contributions()).hasSize(2);
        assertThat(completed.state().pending()).isFalse();
        assertThat(completed.state().accept(refund("REVERSE", 8000)).contributions())
                .isEmpty();
    }

    @Test
    void differentEventIdOfSameBusinessIsDuplicateButDifferentAmountOrSecondActionIsConflict() {
        var first = PaymentProjectionState.empty().accept(sale()).state();
        var original = sale();
        var duplicate = new SettlementFact(
                "new-event",
                original.sourceFactKey(),
                original.kind(),
                original.action(),
                1,
                null,
                10,
                20,
                original.occurredAt(),
                8000,
                "fixed");
        assertThat(first.accept(duplicate).contributions()).isEmpty();
        assertThat(first.accept(refund("REVERSE", 9000)).error()).isEqualTo("REFUND_SOURCE_CONFLICT");
        var refunded = first.accept(refund("REVERSE", 8000)).state();
        assertThat(refunded.accept(refund("RECEIVABLE", 8000)).error()).isEqualTo("BUSINESS_CONFLICT");
    }

    @Test
    void integerOverflowAndInvalidBusinessKeyAreRejected() {
        assertThatThrownBy(() -> new SettlementTotals(1, Long.MAX_VALUE, 0, 0).add(sale()))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new SettlementFact(
                        "x",
                        "forged",
                        SettlementFact.Kind.SALE,
                        "POST",
                        1,
                        null,
                        1,
                        1,
                        LocalDateTime.now(),
                        1,
                        "fixed"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
