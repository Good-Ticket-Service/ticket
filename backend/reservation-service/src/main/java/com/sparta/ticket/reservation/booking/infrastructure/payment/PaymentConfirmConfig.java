package com.sparta.ticket.reservation.booking.infrastructure.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    PaymentConfirmProperties.class,
    PaymentPollingProperties.class,
    AdmissionProperties.class
})
public class PaymentConfirmConfig {}
