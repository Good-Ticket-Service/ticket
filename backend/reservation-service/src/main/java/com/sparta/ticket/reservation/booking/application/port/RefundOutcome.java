package com.sparta.ticket.reservation.booking.application.port;

/** 환불 요청 결과. 다루는 쪽은 Result를 default 없는 switch 식으로 받는다. */
public record RefundOutcome(Result result) {

    public enum Result {
        /** payment가 환불 요청을 받았다. 환불이 끝났는지와는 다르다. 예약은 취소로 끝낸다. */
        ACCEPTED,
        /** 받았는지 모른다. 같은 orderId로 다시 요청한다. */
        UNKNOWN,
        /** payment가 이 orderId를 모른다. 확정한 예약의 결제라 정상 흐름에서는 생기지 않는다. 다시 요청해도 같으므로 알린다. */
        UNKNOWN_ORDER
    }

    public static RefundOutcome accepted() {
        return new RefundOutcome(Result.ACCEPTED);
    }

    public static RefundOutcome unknown() {
        return new RefundOutcome(Result.UNKNOWN);
    }

    public static RefundOutcome unknownOrder() {
        return new RefundOutcome(Result.UNKNOWN_ORDER);
    }
}
