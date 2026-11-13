package com.sparta.ticket.reservation.booking.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.support.SwitchableConfirmSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 승인이 가는 길은 설정 두 값과 Outbox 맨 앞 메시지가 기다린 시간으로 정해진다. 집계가 넘기는 값을 넣고 route()만 본다. */
class ConfirmRouterTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final LocalDateTime AT_THRESHOLD = START.plus(SwitchableConfirmSettings.UNSENT_AFTER);

    private final SwitchableConfirmSettings settings = new SwitchableConfirmSettings();
    private final ConfirmRouter router = new ConfirmRouter(settings, new BookingMetrics(new SimpleMeterRegistry()));

    @ParameterizedTest(name = "메시지 승인={0}, 동기로 돌리기={1}, 맨 앞 메시지 대기={2}초 → {3}")
    @CsvSource({
        "false, true,  0, SYNC",
        "false, true,  60, SYNC",
        "false, false, 0, SYNC",
        "false, false, 60, SYNC",
        "true,  true,  0, MESSAGE",
        "true,  true,  60, SYNC_FALLBACK",
        "true,  false, 0, MESSAGE",
        "true,  false, 60, MESSAGE",
    })
    @DisplayName("설정과 맨 앞 메시지가 기다린 시간으로 길을 정한다")
    void routesBySettingsAndWait(
            boolean viaMessage, boolean fallbackEnabled, long waitedSeconds, ConfirmRoute expected) {
        if (viaMessage) {
            settings.useMessage();
        }
        if (!fallbackEnabled) {
            settings.disableFallback();
        }

        router.update(Optional.of(START), START.plusSeconds(waitedSeconds));

        assertThat(router.route()).isEqualTo(expected);
    }

    @Test
    @DisplayName("맨 앞 메시지가 기준 시간보다 조금이라도 덜 기다렸으면 메시지로 요청한다")
    void staysOnMessageJustBelowThreshold() {
        settings.useMessage();

        router.update(Optional.of(START), AT_THRESHOLD.minusNanos(1));

        assertThat(router.route()).isEqualTo(ConfirmRoute.MESSAGE);
    }

    @Test
    @DisplayName("맨 앞 메시지가 기준 시간만큼 기다렸으면 동기로 부른다")
    void fallsBackAtThreshold() {
        settings.useMessage();

        router.update(Optional.of(START), AT_THRESHOLD);

        assertThat(router.route()).isEqualTo(ConfirmRoute.SYNC_FALLBACK);
    }

    @Test
    @DisplayName("집계가 한 번도 돌지 않았으면 메시지로 요청한다")
    void startsOnMessage() {
        settings.useMessage();

        assertThat(router.route()).isEqualTo(ConfirmRoute.MESSAGE);
    }

    @Test
    @DisplayName("Outbox가 비면 다시 메시지로 요청한다")
    void returnsWhenOutboxEmpties() {
        settings.useMessage();
        router.update(Optional.of(START), START.plusMinutes(1));

        router.update(Optional.empty(), START.plusMinutes(1).plusSeconds(1));

        assertThat(router.route()).isEqualTo(ConfirmRoute.MESSAGE);
    }

    @Test
    @DisplayName("오래 기다린 메시지가 나가고 방금 남긴 메시지만 있으면 다시 메시지로 요청한다")
    void returnsWhenOnlyYoungMessagesWait() {
        settings.useMessage();
        LocalDateTime later = START.plusMinutes(1);
        router.update(Optional.of(START), later);

        router.update(Optional.of(later), later.plusSeconds(1));

        assertThat(router.route()).isEqualTo(ConfirmRoute.MESSAGE);
    }

    @Test
    @DisplayName("집계 뒤에 설정을 메시지 승인으로 바꿔도 다음 요청부터 맨 앞 메시지의 판단을 따른다")
    void readsSettingsPerRequest() {
        router.update(Optional.of(START), START.plusMinutes(1));

        settings.useMessage();

        assertThat(router.route()).isEqualTo(ConfirmRoute.SYNC_FALLBACK);
    }

    @Test
    @DisplayName("시계가 뒤로 가 기다린 시간이 음수면 동기로 돌리지 않는다")
    void clockGoingBackDoesNotFallBack() {
        settings.useMessage();

        router.update(Optional.of(START), START.minusSeconds(30));

        assertThat(router.route()).isEqualTo(ConfirmRoute.MESSAGE);
    }
}
