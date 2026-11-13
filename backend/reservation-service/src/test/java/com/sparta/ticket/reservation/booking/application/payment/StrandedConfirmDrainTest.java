package com.sparta.ticket.reservation.booking.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.booking.application.port.StrandedConfirms;
import com.sparta.ticket.reservation.booking.support.FakeSubstituteConfirm;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.booking.support.SwitchableConfirmSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 고른 뒤 보내기 전에 시간이 흐르는 경우는 DB로 만들 수 없어, 고르는 쪽을 대역으로 두고 본다. */
class StrandedConfirmDrainTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 20, 0);

    private final MutableClock clock = new MutableClock(START);
    private final SwitchableConfirmSettings settings = new SwitchableConfirmSettings();
    private final BookingMetrics metrics = new BookingMetrics(new SimpleMeterRegistry());
    private final ConfirmRouter router = new ConfirmRouter(settings, metrics);
    private final FakeSubstituteConfirm substitute = new FakeSubstituteConfirm();

    @Test
    @DisplayName("고른 뒤 보내기 전에 시한이 지나면 보내지 않는다")
    void doesNotSendAfterDeadlinePassedSinceClaim() {
        settings.useMessage();
        settings.enableDrain();
        router.update(Optional.of(START.minus(SwitchableConfirmSettings.UNSENT_AFTER)), START);
        PaymentConfirmRequest request = new PaymentConfirmRequest("order-1", 1L, 10_000, "pk", START.plusSeconds(1));
        StrandedConfirms stranded = new StrandedConfirms() {
            @Override
            public Optional<ClaimedConfirm> claimNext(LocalDateTime now, Duration resendAfter) {
                clock.advance(Duration.ofSeconds(1));
                return Optional.of(new ClaimedConfirm(request, false));
            }

            @Override
            public void requeue(PaymentConfirmRequest request) {}

            @Override
            public void parkUntilDeadline(PaymentConfirmRequest request) {}
        };
        StrandedConfirmDrain drain = new StrandedConfirmDrain(
                router,
                settings,
                stranded,
                substitute,
                mock(PaymentResultApplier.class),
                new ChangeStamps(clock, Optional::empty),
                metrics);

        assertThat(drain.sendNext()).isTrue();

        assertThat(substitute.received).isEmpty();
    }
}
