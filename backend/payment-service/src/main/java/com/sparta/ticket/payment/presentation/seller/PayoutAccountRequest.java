package com.sparta.ticket.payment.presentation.seller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 수취 계좌 등록·변경 본문. 계좌번호·예금주 원문은 마스킹·모의 참조로 바꾼 뒤 버린다.
 * Note: toString을 기본 record 형식으로 두면 원문이 로그에 찍힐 수 있어 가린다.
 */
public record PayoutAccountRequest(
        @NotBlank @Pattern(regexp = "\\d{3}") String bankCode,
        @NotBlank @Pattern(regexp = "[0-9-]{6,30}") String accountNumber,
        @NotBlank @Size(max = 50) String holderName) {

    @Override
    public String toString() {
        return "PayoutAccountRequest[bankCode=" + bankCode + ", accountNumber=***, holderName=***]";
    }
}
