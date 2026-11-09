package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.PaymentPollingSettings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 결제 결과 폴링 간격 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다. */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.payment-polling")
public record PaymentPollingProperties(
        @NotNull @DurationMin(seconds = 1) Duration base,
        @NotNull @DurationMin(seconds = 1) Duration max,
        @Positive long busyAbove)
        implements PaymentPollingSettings {}
