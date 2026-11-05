package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;

/** 승인 시작 커밋에 성공한 호출자에게만 전달하는 실행 정보. 토스 거래 식별자는 인증값이므로 문자열 출력에서 숨긴다. */
public record ApprovalClaim(
        Long paymentId, String orderId, PgProvider provider, String transactionId, int amount, Long userId) {
    @Override
    public String toString() {
        return "ApprovalClaim[paymentId=" + paymentId + "]";
    }
}
