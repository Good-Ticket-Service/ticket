package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.PayoutProviderStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** 지급 조회 일정(settlement-design 7.2절, 2026-10-30 개정). 지터 0으로 고정해 경계를 확인한다. */
class PayoutInquiryScheduleTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 12, 4, 10);

    private final PayoutInquirySchedule schedule = new PayoutInquirySchedule(
            Duration.ofSeconds(30),
            Duration.ofSeconds(300),
            Duration.ofMinutes(60),
            Duration.ofSeconds(600),
            Duration.ofMinutes(60),
            Duration.ofMinutes(180),
            0.2,
            () -> 0.5);

    /** 일정 계산에 쓰는 필드만 채운 REQUESTED 지급. */
    private static Payout requested(PayoutProviderStatus status, boolean inquiryOnly) {
        Payout payout = BeanUtils.instantiateClass(Payout.class);
        ReflectionTestUtils.setField(payout, "requestedAt", REQUESTED_AT);
        ReflectionTestUtils.setField(payout, "providerStatus", status);
        ReflectionTestUtils.setField(payout, "inquiryOnly", inquiryOnly);
        return payout;
    }

    @Test
    void unknownWaitsAboutElapsedTimeBetween30And300Seconds() {
        Payout unknown = requested(PayoutProviderStatus.UNKNOWN, false);

        assertThat(schedule.next(unknown, REQUESTED_AT.plusSeconds(1))).isEqualTo(REQUESTED_AT.plusSeconds(31));
        assertThat(schedule.next(unknown, REQUESTED_AT.plusSeconds(120))).isEqualTo(REQUESTED_AT.plusSeconds(240));
        assertThat(schedule.next(unknown, REQUESTED_AT.plusMinutes(30)))
                .isEqualTo(REQUESTED_AT.plusMinutes(30).plusSeconds(300));
    }

    @Test
    void unknownAfterSixtyMinutesSlowsToSixHundredSeconds() {
        Payout unknown = requested(PayoutProviderStatus.UNKNOWN, false);
        LocalDateTime now = REQUESTED_AT.plusMinutes(61);

        assertThat(schedule.next(unknown, now)).isEqualTo(now.plusSeconds(600));
    }

    @Test
    void acceptedWaitsExpectedProcessingThenLongInterval() {
        Payout accepted = requested(PayoutProviderStatus.ACCEPTED, false);

        assertThat(schedule.next(accepted, REQUESTED_AT.plusSeconds(1))).isEqualTo(REQUESTED_AT.plusMinutes(60));
        LocalDateTime later = REQUESTED_AT.plusMinutes(61);
        assertThat(schedule.next(accepted, later)).isEqualTo(later.plusMinutes(180));
    }

    @Test
    void inquiryOnlyUsesShortScheduleEvenIfAccepted() {
        Payout blocked = requested(PayoutProviderStatus.PROCESSING, true);

        assertThat(schedule.next(blocked, REQUESTED_AT.plusSeconds(1))).isEqualTo(REQUESTED_AT.plusSeconds(31));
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        Payout accepted = requested(PayoutProviderStatus.ACCEPTED, false);
        LocalDateTime later = REQUESTED_AT.plusMinutes(61);
        PayoutInquirySchedule low = new PayoutInquirySchedule(
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                Duration.ofMinutes(60),
                Duration.ofSeconds(600),
                Duration.ofMinutes(60),
                Duration.ofMinutes(180),
                0.2,
                () -> 0.0);

        assertThat(low.next(accepted, later)).isEqualTo(later.plusMinutes(144));
    }
}
