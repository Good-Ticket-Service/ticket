package com.sparta.ticket.fakepg.application.service.payout;

/** 지급대행 요청 하나. 금액·계좌·판매자가 같아야 같은 요청이다. */
public record PayoutCommand(
        String idempotencyKey, String sellerRef, String accountRef, String holderMasked, long amount) {}
