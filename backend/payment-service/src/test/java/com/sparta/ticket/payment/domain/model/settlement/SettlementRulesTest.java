package com.sparta.ticket.payment.domain.model.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 정산 금액·정책 버전·정산 가능일 계산(settlement-design 2절, 시험 S1). DB 없이 순수 계산만 본다. */
class SettlementRulesTest {

    private static final LocalDateTime V1_FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final SettlementPolicy V1 = policy("v1", V1_FROM, 1200);
    private static final BusinessCalendar CALENDAR = new BusinessCalendar(
            "cal-1",
            V1_FROM,
            LocalDate.of(2026, 12, 31),
            Set.of(
                    LocalDate.of(2026, 9, 24),
                    LocalDate.of(2026, 9, 25),
                    LocalDate.of(2026, 9, 26),
                    LocalDate.of(2026, 12, 25)));

    private static SettlementPolicy policy(String version, LocalDateTime from, int commissionBp) {
        return new SettlementPolicy(version, from, commissionBp, 1000, 340, 1000, true, 3);
    }

    private static SaleAmounts calculated(int gross, int discount, int bookingFee, SettlementPolicy policy) {
        SaleAmounts.Result result =
                SaleAmounts.calculate(gross, discount, bookingFee, gross - discount + bookingFee, policy);
        assertThat(result).isInstanceOf(SaleAmounts.Calculated.class);
        return ((SaleAmounts.Calculated) result).amounts();
    }

    private static SaleSource source(
            LocalDateTime approvedAt, LocalDateTime scheduleStartAt, int gross, int discount, int paid) {
        return new SaleSource(1L, 2L, 3L, 4L, scheduleStartAt, approvedAt, gross, discount, 0, paid);
    }

    @Test
    void splitsPaymentAndKeepsConservation() {
        SaleAmounts a = calculated(50_000, 1_500, 1_000, V1);

        assertThat(a.paid()).isEqualTo(49_500);
        assertThat(a.commission()).isEqualTo(6_000);
        assertThat(a.commissionVat()).isEqualTo(600);
        assertThat(a.sellerAmount()).isEqualTo(43_400);
        assertThat(a.bookingFeeNet()).isEqualTo(909);
        assertThat(a.bookingFeeVat()).isEqualTo(91);
        assertThat(ExpectedProfit.of(a, V1, false)).isEqualTo(new ExpectedProfit(1_851, 3_558));
        assertThat(ExpectedProfit.of(a, V1, true)).isEqualTo(new ExpectedProfit(0, 0));
    }

    @ParameterizedTest
    @CsvSource({
        "1, 0, 0",
        "99, 0, 0",
        "10001, 0, 1",
        "2147483647, 0, 0",
        "2147480647, 2147480647, 3000",
        "33333, 33332, 1"
    })
    void conservationHoldsAtBoundaries(int gross, int discount, int bookingFee) {
        SaleAmounts a = calculated(gross, discount, bookingFee, V1);

        assertThat((long) a.paid() + a.discount())
                .isEqualTo((long) a.sellerAmount()
                        + a.commission()
                        + a.commissionVat()
                        + a.bookingFeeNet()
                        + a.bookingFeeVat());
        assertThat(a.sellerAmount()).isNotNegative();
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0, 0, 0, INVALID_INPUT",
        "1000, -1, 0, 1001, INVALID_INPUT",
        "1000, 0, -1, 999, INVALID_INPUT",
        "1000, 1001, 0, -1, DISCOUNT_EXCEEDS_GROSS",
        "1000, 100, 0, 1000, PAID_AMOUNT_MISMATCH",
        "2147483647, 0, 1, -2147483648, PAID_AMOUNT_MISMATCH"
    })
    void rejectsInvalidInputWithoutCorrectingAmount(
            int gross, int discount, int bookingFee, int paid, SettlementFailure reason) {
        assertThat(SaleAmounts.calculate(gross, discount, bookingFee, paid, V1))
                .isEqualTo(new SaleAmounts.Rejected(reason));
    }

    @Test
    void selectsPolicyByApprovalTimeAndKeepsPastVersion() {
        LocalDateTime v2From = LocalDateTime.of(2026, 11, 1, 0, 0);
        SettlementRules rules = new SettlementRules(
                new SettlementPolicyBook(List.of(policy("v2", v2From, 1500), V1)),
                new BusinessCalendarBook(List.of(CALENDAR)));
        LocalDateTime show = LocalDateTime.of(2026, 11, 10, 19, 0);

        SettlementItem before = rules.saleFor(source(v2From.minusNanos(1000), show, 10_000, 0, 10_000));
        SettlementItem after = rules.saleFor(source(v2From, show, 10_000, 0, 10_000));
        SettlementItem tooEarly = rules.saleFor(source(V1_FROM.minusDays(1), show, 10_000, 0, 10_000));

        assertThat(before.getPolicyVersion()).isEqualTo("v1");
        assertThat(before.getCommission()).isEqualTo(1_200);
        assertThat(after.getPolicyVersion()).isEqualTo("v2");
        assertThat(after.getCommission()).isEqualTo(1_500);
        assertThat(tooEarly.getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(tooEarly.getFailureReason()).isEqualTo(SettlementFailure.POLICY_NOT_FOUND);
        assertThat(rules.policy("v1")).contains(V1);
    }

    @ParameterizedTest
    @CsvSource({
        // 회차 시작일, 정산 가능일(다음 날부터 3번째 영업일)
        "2026-11-04T19:00, 2026-11-09T00:00", // 수 → 목·금·월
        "2026-11-06T23:59, 2026-11-11T00:00", // 금 → 월·화·수
        "2026-11-07T14:00, 2026-11-11T00:00", // 토 → 월·화·수
        "2026-09-22T19:00, 2026-09-29T00:00", // 수(1) → 추석(9/24~26)·일요일 건너뜀 → 월(2)·화(3)
        "2026-12-23T19:00, 2026-12-29T00:00" // 성탄절
    })
    void eligibleAtCountsBusinessDaysAfterShowDate(LocalDateTime show, LocalDateTime eligible) {
        assertThat(CALENDAR.eligibleAt(show, 3)).contains(eligible);
    }

    @Test
    void holdsInsteadOfGuessingWhenScheduleTimeOrCalendarIsMissing() {
        SettlementRules rules =
                new SettlementRules(new SettlementPolicyBook(List.of(V1)), new BusinessCalendarBook(List.of(CALENDAR)));
        LocalDateTime approved = LocalDateTime.of(2026, 12, 1, 10, 0);

        SettlementItem pending =
                rules.saleFor(source(approved, LocalDateTime.of(2026, 12, 2, 19, 0), 10_000, 0, 10_000));
        SettlementItem noSchedule = rules.saleFor(source(approved, null, 10_000, 0, 10_000));
        SettlementItem beyondCalendar =
                rules.saleFor(source(approved, LocalDateTime.of(2026, 12, 30, 19, 0), 10_000, 0, 10_000));
        SettlementItem mismatch =
                rules.saleFor(source(approved, LocalDateTime.of(2026, 12, 2, 19, 0), 10_000, 0, 9_000));

        assertThat(pending.getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(pending.getEligibleAt()).isEqualTo(LocalDateTime.of(2026, 12, 7, 0, 0));
        assertThat(noSchedule.getHoldReason()).isEqualTo(SettlementHoldReason.MISSING_SCHEDULE_START);
        assertThat(noSchedule.getSellerAmount()).isEqualTo(8_680);
        assertThat(beyondCalendar.getHoldReason()).isEqualTo(SettlementHoldReason.CALENDAR_NOT_COVERED);
        assertThat(mismatch.getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(mismatch.getFailureReason()).isEqualTo(SettlementFailure.PAID_AMOUNT_MISMATCH);
        assertThat(mismatch.getSellerAmount()).isNull();
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> new SettlementPolicyBook(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementPolicyBook(List.of(V1, policy("v1", V1_FROM.plusDays(1), 1200))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementPolicyBook(List.of(V1, policy("v2", V1_FROM, 1200))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("v3", V1_FROM, 9_500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new BusinessCalendar("c", V1_FROM, LocalDate.of(2026, 1, 31), Set.of(LocalDate.of(2026, 2, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
