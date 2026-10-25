package com.sparta.ticket.reservation.application.port;

/**
 * booking이 payment에 바라는 기능. payment의 요청·응답 형식은 어댑터가 이 타입으로 바꾼다.
 * 계약(orchestration.md P1, P2)이 정해지기 전에는 Feign 어댑터를 만들지 않고, 테스트는 가짜 구현으로 한다.
 */
public interface PaymentPort {

    /** 결제를 준비한다. 쿠폰을 적용한 결제 금액을 돌려준다. 거절되거나 결과를 모르면 예외를 던진다. */
    PreparedPayment prepare(PaymentPrepareRequest request);

    /** 결제를 승인한다. 같은 orderId로 다시 보내도 payment가 처음 결과를 돌려준다. 결과를 모르면 예외를 던진다. */
    ConfirmOutcome confirm(String orderId, String pgAuthToken, long amount);

    /** 승인 결과를 조회한다. 결과 확인 스케줄러가 쓴다. */
    PaymentLookup find(String orderId);
}
