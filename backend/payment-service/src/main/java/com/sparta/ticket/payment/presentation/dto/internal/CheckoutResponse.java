package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.payment.CheckoutResult;

/** POST /internal/payments 응답. status는 READY 또는 FAILED(카카오 준비 실패 등)다. */
public record CheckoutResponse(
        Long paymentId,
        String orderId,
        int amount,
        String status,
        String failureType,
        String failureCode,
        String checkoutUrl) {

    public static CheckoutResponse from(CheckoutResult result) {
        return new CheckoutResponse(
                result.paymentId(),
                result.orderId(),
                result.amount(),
                result.status().name(),
                result.failureType() == null ? null : result.failureType().name(),
                result.failureCode(),
                result.checkoutUrl());
    }
}
