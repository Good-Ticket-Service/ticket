package com.sparta.ticket.reservation.booking.infrastructure.payment;

/** POST /internal/payments/{orderId}/refund 요청. reason은 USER_CANCEL, SCHEDULE_CANCELED, CONFIRM_FAILED 중 하나다. */
public record PaymentRefundRequest(String reason) {}
