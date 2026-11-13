package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 결제 승인 방식 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다. */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.payment-confirm")
public record PaymentConfirmProperties(
        boolean viaMessage,
        @NotNull @DurationMin(seconds = 1) Duration window,
        @NotNull @DurationMin(seconds = 1) Duration grace,
        @Valid @NotNull Fallback fallback)
        implements PaymentConfirmSettings {

    /**
     * @param enabled 끄면 승인 요청이 브로커로 나가지 못해도 메시지로 요청한다
     * @param unsentAfter Outbox 맨 앞 메시지가 이만큼 기다리면 새 승인을 동기로 부른다. 결제 중 집계가 1초마다 재므로 1초보다 짧게 둘 수 없다
     */
    public record Fallback(
            boolean enabled,
            @NotNull @DurationMin(seconds = 1) Duration unsentAfter) {}

    @Override
    public boolean fallbackEnabled() {
        return fallback.enabled();
    }

    @Override
    public Duration unsentAfter() {
        return fallback.unsentAfter();
    }

    /** 시한이 지난 요청은 브로커가 돌아와도 payment가 PG를 부르지 않는다. 그때까지 메시지로 받으면 동기로 돌려도 구할 승인이 없다. */
    @AssertTrue(message = "fallback.unsent-after는 window보다 짧아야 한다")
    public boolean isUnsentAfterShorterThanWindow() {
        return fallback == null
                || fallback.unsentAfter() == null
                || window == null
                || fallback.unsentAfter().compareTo(window) < 0;
    }
}
