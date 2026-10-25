package com.sparta.ticket.reservation.presentation.dto;

import com.sparta.ticket.reservation.application.dto.CheckoutResult;

public record CheckoutResponse(String orderId, long amount, String paymentWindowUrl) {

    public static CheckoutResponse from(CheckoutResult result) {
        return new CheckoutResponse(result.orderId(), result.amount(), result.paymentWindowUrl());
    }
}
