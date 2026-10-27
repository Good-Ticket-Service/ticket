package com.sparta.ticket.reservation.booking.application.port;

/** 결제 조회 결과. 다루는 쪽은 Status를 default 없는 switch 식으로 받는다. */
public record PaymentLookup(Status status, String failureReason) {

    public enum Status {
        /** 승인 확정. */
        SUCCEEDED,
        /** 돈이 오가지 않음으로 확정. */
        FAILED,
        /** 준비만 됐다. 승인 요청을 아직 받지 못했거나 처리 전이다. 같은 승인을 다시 보낸다. */
        NOT_RECEIVED,
        /** 승인 요청을 받아 PG를 불렀고 결과를 모른다. 기다린다. */
        IN_PROGRESS,
        /** payment가 이 orderId를 모른다. 준비가 성공한 뒤에만 order_id를 기록하므로 생기면 안 된다. */
        UNKNOWN_ORDER
    }

    public static PaymentLookup of(Status status) {
        return new PaymentLookup(status, null);
    }

    public static PaymentLookup failed(String reason) {
        return new PaymentLookup(Status.FAILED, reason);
    }

    public ConfirmOutcome toOutcome() {
        return switch (status) {
            case SUCCEEDED -> ConfirmOutcome.succeeded();
            case FAILED -> ConfirmOutcome.failed(failureReason);
            case NOT_RECEIVED, IN_PROGRESS, UNKNOWN_ORDER -> ConfirmOutcome.unknown();
        };
    }
}
