package com.sparta.ticket.payment.domain.model.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 환불·보충에 따른 SALE 상태 전이(Task 20-4). */
class SettlementItemTransitionTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final SettlementRules RULES = new SettlementRules(
            new SettlementPolicyBook(
                    List.of(new SettlementPolicy("v1", AT.minusYears(1), 1200, 1000, 340, 1000, true, 3))),
            new BusinessCalendarBook(
                    List.of(new BusinessCalendar("cal-1", AT.minusYears(1), LocalDate.of(2026, 12, 31), Set.of()))));

    private static SaleSource source(LocalDateTime approvedAt) {
        return new SaleSource(1L, 2L, 3L, 4L, AT.plusDays(5), approvedAt, 10_000, 0, 0, 10_000);
    }

    @Test
    void refundCancelsOnlySaleBeforeStatement() {
        SettlementItem pending = RULES.saleFor(source(AT));
        pending.cancelByRefund(7L);
        assertThat(pending.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        assertThat(pending.getRefundId()).isEqualTo(7L);

        SettlementItem unposted = RULES.saleFor(source(AT.minusYears(2)));
        assertThatThrownBy(() -> unposted.cancelByRefund(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unpostedSaleAdoptsRecalculationAndKeepsRefund() {
        SettlementItem unposted = RULES.saleFor(source(AT.minusYears(2)));
        assertThat(unposted.getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        unposted.holdForRefund(7L);

        unposted.adoptCalculation(RULES.saleFor(source(AT)));

        assertThat(unposted.getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(unposted.getFailureReason()).isNull();
        assertThat(unposted.getRefundId()).isEqualTo(7L);
        assertThat(unposted.amounts().sellerAmount()).isEqualTo(8_680);
        assertThatThrownBy(() -> unposted.adoptCalculation(RULES.saleFor(source(AT))))
                .isInstanceOf(IllegalStateException.class);
    }
}
