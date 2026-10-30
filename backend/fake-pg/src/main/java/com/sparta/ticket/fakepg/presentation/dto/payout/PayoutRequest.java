package com.sparta.ticket.fakepg.presentation.dto.payout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 지급대행 요청 본문. 계좌번호 원문 대신 모의 계좌 참조와 마스킹한 예금주만 받는다. */
public record PayoutRequest(
        @NotBlank @Size(max = 100) String sellerRef,
        @NotBlank @Size(max = 100) String accountRef,
        @NotBlank @Size(max = 100) String holderMasked,
        @Positive long amount) {}
