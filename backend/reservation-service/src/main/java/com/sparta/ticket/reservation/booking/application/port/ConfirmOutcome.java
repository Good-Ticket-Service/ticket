package com.sparta.ticket.reservation.booking.application.port;

/** 결제 승인 결과. 다루는 쪽은 Result를 default 없는 switch 식으로 받아 빠진 경우를 컴파일러가 잡게 한다. */
public record ConfirmOutcome(Result result, String failureReason) {

    public enum Result {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    public static ConfirmOutcome succeeded() {
        return new ConfirmOutcome(Result.SUCCEEDED, null);
    }

    public static ConfirmOutcome failed(String reason) {
        return new ConfirmOutcome(Result.FAILED, reason);
    }

    public static ConfirmOutcome unknown() {
        return new ConfirmOutcome(Result.UNKNOWN, null);
    }
}
