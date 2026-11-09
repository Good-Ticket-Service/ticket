package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
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
        @NotNull @DurationMin(seconds = 1) Duration grace)
        implements PaymentConfirmSettings {}
