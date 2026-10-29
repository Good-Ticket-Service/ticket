package com.sparta.ticket.reservation.booking.infrastructure.payment;

/**
 * POST /internal/payments/{orderId}/refund 응답. refundStatus는 WAITING, REQUESTED, DONE, FAILED, SKIPPED, NOT_REQUIRED
 * 중 하나다.
 */
public record PaymentRefundResponse(String refundStatus, Integer refundAmount) {}
