package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.DrainSettings;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
     * @param drain 동기로 돌리는 동안 이미 Outbox에 남은 승인 요청을 HTTP로 대신 보내는 작업자
     */
    public record Fallback(
            boolean enabled,
            @NotNull @DurationMin(seconds = 1) Duration unsentAfter,
            @Valid @NotNull Drain drain) {}

    /**
     * @param concurrency 작업자 스레드 수. 집기와 결과 반영이 완료 전용 풀(5개)을 쓰는데 발행기와 결제 중 집계가 하나씩 쓰므로 4를 넘지 않는다
     * @param resendAfter 1초보다 짧으면 payment가 받았다고만 답한 요청을 PG가 끝내기도 전에 계속 다시 묻는다
     * @param maxBackoff payment가 돌려보낼 때 쉬는 시간의 상한
     */
    public record Drain(
            boolean enabled,
            @Min(1) @Max(4) int concurrency,
            @NotNull @DurationMin(seconds = 1) Duration resendAfter,
            @NotNull Duration maxBackoff)
            implements DrainSettings {

        /** 받을 자리가 없다고 돌려보낸 payment에, 받았다고 답한 요청을 다시 묻는 간격보다 자주 보내지 않는다. */
        @AssertTrue(message = "fallback.drain.max-backoff는 resend-after보다 짧을 수 없다")
        public boolean isMaxBackoffNotShorterThanResendAfter() {
            return resendAfter == null || maxBackoff == null || maxBackoff.compareTo(resendAfter) >= 0;
        }
    }

    @Override
    public boolean fallbackEnabled() {
        return fallback.enabled();
    }

    @Override
    public Duration unsentAfter() {
        return fallback.unsentAfter();
    }

    @Override
    public DrainSettings drain() {
        return fallback.drain();
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
