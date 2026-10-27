package com.sparta.ticket.fakepg.presentation.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import java.time.LocalDateTime;

/**
 * 카카오페이 승인·조회·취소 응답 중 payment-service가 쓰는 필드. 세 API가 같은 모양을 쓴다.
 * 시각은 카카오처럼 오프셋 없는 한국 시간이다. 수량은 저장하지 않아 1로 돌려준다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoPaymentResponse(
        String aid,
        String tid,
        String cid,
        String status,
        String partnerOrderId,
        String partnerUserId,
        String paymentMethodType,
        Amount amount,
        Amount canceledAmount,
        Amount cancelAvailableAmount,
        String itemName,
        int quantity,
        LocalDateTime createdAt,
        LocalDateTime approvedAt,
        LocalDateTime canceledAt) {

    public static KakaoPaymentResponse from(PgTransaction transaction, String cid) {
        boolean canceled = transaction.getStatus() == PgTransactionStatus.CANCELED;
        int total = transaction.getAmount();
        return new KakaoPaymentResponse(
                transaction.getApprovedAt() == null
                        ? null
                        : "A" + transaction.getPaymentKey().substring(1),
                transaction.getPaymentKey(),
                cid,
                status(transaction),
                transaction.getOrderId(),
                transaction.getPartnerUserId(),
                "MONEY",
                Amount.of(total),
                Amount.of(canceled ? total : 0),
                Amount.of(transaction.getStatus() == PgTransactionStatus.DONE ? total : 0),
                transaction.getOrderName(),
                1,
                transaction.getRequestedAt(),
                transaction.getApprovedAt(),
                transaction.getCanceledAt());
    }

    /**
     * 거래 상태를 카카오 결제 상태로 바꾼다. 준비 후 사용자 승인 전은 READY, 사용자 승인 후 승인 전은 AUTH_PASSWORD다.
     * 인증 유효 시간이 지난 결제는 사용자가 결제를 끝내지 않은 것으로 보고 QUIT_PAYMENT다.
     */
    private static String status(PgTransaction transaction) {
        return switch (transaction.getStatus()) {
            case READY -> transaction.getPgToken() == null ? "READY" : "AUTH_PASSWORD";
            case DONE -> "SUCCESS_PAYMENT";
            case CANCELED -> "CANCEL_PAYMENT";
            case ABORTED -> "FAIL_PAYMENT";
            case EXPIRED -> "QUIT_PAYMENT";
        };
    }

    /** 카카오 금액 객체. 부가세는 카카오가 자동 계산하는 방식((총액 - 비과세) / 11)을 따른다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Amount(int total, int taxFree, int vat, int point, int discount) {

        static Amount of(int total) {
            return new Amount(total, 0, total / 11, 0, 0);
        }
    }
}
