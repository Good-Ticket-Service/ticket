package com.sparta.ticket.payment.application.service.pgsettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.pgsettlement.PgFeePolicy;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import com.sparta.ticket.payment.domain.model.settlement.BusinessCalendar;
import com.sparta.ticket.payment.domain.model.settlement.BusinessCalendarBook;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PgSettlementRulesTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final PgFeePolicy OLD = new PgFeePolicy("TOSS", "v1", START, 320, 1000, 2);
    private static final PgFeePolicy NEW = new PgFeePolicy("TOSS", "v2", START.plusMonths(6), 400, 1000, 2);
    private static final PgFeePolicy KAKAO = new PgFeePolicy("KAKAO", "v1", START, 300, 1000, 3);
    private final PgSettlementRules rules = new PgSettlementRules(
            new PgSettlementProperties(List.of(OLD, NEW, KAKAO), false, START.toLocalDate(), 2000, 1),
            new BusinessCalendarBook(List.of(new BusinessCalendar(
                    "cal",
                    START,
                    LocalDate.of(2027, 12, 31),
                    Set.of(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5))))));

    @Test
    void holidayAndWeekendDoNotCountAsBusinessDays() {
        assertThat(rules.date(LocalDateTime.of(2026, 4, 30, 23, 59), 2)).isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    void cancellationRefundsOriginalFeeEvenAfterRateChange() {
        var approved = LocalDateTime.of(2026, 6, 29, 10, 0);
        var canceled = LocalDateTime.of(2026, 7, 2, 10, 0);
        var expected = new PgSettlement.Expected("key", "order", "CANCEL", -10000, canceled, false, true, "CANCELED");
        var line = new PgSettlement.Line("key:CANCEL", "key", "order", "CANCEL", -10000, -352, canceled);
        var decision = rules.decide("TOSS", LocalDate.of(2026, 7, 6), line, expected, approved);
        assertThat(decision.finding()).isEqualTo(PgSettlement.Finding.MATCHED);
        assertThat(decision.policyVersion()).isEqualTo("v1");
        assertThat(decision.expectedFee()).isEqualTo(-352);
    }

    @Test
    void preSettlementCancellationRetainsBothEventsOnTheSameDay() {
        var approved = LocalDateTime.of(2026, 6, 29, 23, 59, 59);
        var canceled = approved.plusSeconds(2);
        var line = new PgSettlement.Line("key:CANCEL", "key", "order", "CANCEL", -10000, -352, canceled);
        var expected = new PgSettlement.Expected("key", "order", "CANCEL", -10000, canceled, false, true, "CANCELED");
        assertThat(rules.decide("TOSS", LocalDate.of(2026, 7, 1), line, expected, approved)
                        .finding())
                .isEqualTo(PgSettlement.Finding.MATCHED);
    }

    @Test
    void inProgressFactRemainsPendingAndDoesNotClearPrincipal() {
        var approved = START.plusDays(20);
        var line = new PgSettlement.Line("key:APPROVAL", "key", "order", "APPROVAL", 10000, 352, approved);
        var expected =
                new PgSettlement.Expected("key", "order", "APPROVAL", 10000, approved, true, true, "IN_PROGRESS");
        var decision = rules.decide("TOSS", START.toLocalDate().plusDays(22), line, expected, approved);
        assertThat(decision.finding()).isEqualTo(PgSettlement.Finding.PENDING);
        assertThat(decision.clearingAmount()).isZero();
    }

    @Test
    void duplicateEffectiveDatesFailAtStartup() {
        assertThatThrownBy(
                        () -> new PgSettlementProperties(List.of(OLD, OLD, KAKAO), false, START.toLocalDate(), 2000, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chargeFoundAfterFinalCheckpointIsUrgentRatherThanPendingForever() {
        var line = new PgSettlement.Line("key:APPROVAL", "key", "order", "APPROVAL", 10000, 352, START);
        var expected = new PgSettlement.Expected("key", "order", "APPROVAL", 10000, null, false, false, "FAILED");
        assertThat(rules.decide("TOSS", START.toLocalDate().plusDays(3), line, expected, null)
                        .finding())
                .isEqualTo(PgSettlement.Finding.ONLY_IN_PG);
    }
}
