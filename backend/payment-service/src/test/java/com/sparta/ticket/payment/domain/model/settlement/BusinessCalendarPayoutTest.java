package com.sparta.ticket.payment.domain.model.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 지급 가능 시각: 그날 이후 첫 영업일의 지급 시작 시각(settlement-design 5절 1번, 7.2절 비영업일). */
class BusinessCalendarPayoutTest {

    private static final LocalTime PAYOUT_AT = LocalTime.of(4, 10);
    private static final BusinessCalendar CALENDAR = new BusinessCalendar(
            "cal-1",
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDate.of(2026, 12, 31),
            Set.of(LocalDate.of(2026, 12, 25)));

    @Test
    void sameBusinessDayBeforePayoutTime() {
        // 2026-11-12(목) 04:00 발행 → 같은 날 04:10
        assertThat(CALENDAR.nextPayoutAt(LocalDateTime.of(2026, 11, 12, 4, 0), PAYOUT_AT))
                .contains(LocalDateTime.of(2026, 11, 12, 4, 10));
    }

    @Test
    void weekendHolidayAndLateTimeMoveToNextBusinessDay() {
        // 토요일 → 월요일
        assertThat(CALENDAR.nextPayoutAt(LocalDateTime.of(2026, 11, 14, 4, 0), PAYOUT_AT))
                .contains(LocalDateTime.of(2026, 11, 16, 4, 10));
        // 지급 시각이 지난 영업일 → 다음 영업일
        assertThat(CALENDAR.nextPayoutAt(LocalDateTime.of(2026, 11, 12, 9, 0), PAYOUT_AT))
                .contains(LocalDateTime.of(2026, 11, 13, 4, 10));
        // 성탄절(금) → 월요일
        assertThat(CALENDAR.nextPayoutAt(LocalDateTime.of(2026, 12, 25, 4, 0), PAYOUT_AT))
                .contains(LocalDateTime.of(2026, 12, 28, 4, 10));
    }

    @Test
    void outsideCalendarIsUnknown() {
        assertThat(CALENDAR.nextPayoutAt(LocalDateTime.of(2026, 12, 31, 9, 0), PAYOUT_AT))
                .isEmpty();
    }
}
