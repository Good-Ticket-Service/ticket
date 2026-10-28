package com.sparta.ticket.reservation.booking.infrastructure.payment;

/** POST /internal/payments/{orderId}/confirm 응답. status는 DONE, FAILED, IN_PROGRESS 중 하나다. */
public record PaymentConfirmResponse(Long paymentId, String status, String failureType, String failureCode) {}
