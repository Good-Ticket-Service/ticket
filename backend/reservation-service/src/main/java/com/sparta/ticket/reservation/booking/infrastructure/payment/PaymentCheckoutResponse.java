package com.sparta.ticket.reservation.booking.infrastructure.payment;

/** POST /internal/payments 응답. HTTP 200이어도 status가 FAILED일 수 있다. */
public record PaymentCheckoutResponse(
        Long paymentId,
        String orderId,
        Integer amount,
        String status,
        String failureType,
        String failureCode,
        String checkoutUrl) {}
