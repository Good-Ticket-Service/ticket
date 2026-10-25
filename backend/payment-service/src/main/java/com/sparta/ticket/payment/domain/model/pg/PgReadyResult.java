package com.sparta.ticket.payment.domain.model.pg;

/**
 * 결제 준비 결과. 카카오는 PG 준비를 불러 tid와 결제창 URL을 받고, 토스는 PG를 부르지 않아 둘 다 null이다.
 * 준비는 돈이 오가지 않으므로 거절과 결과 불명을 구분하지 않고 Failed 하나로 둔다.
 */
public sealed interface PgReadyResult {

    record Ready(String transactionId, String checkoutUrl) implements PgReadyResult {}

    record Failed(String reason) implements PgReadyResult {}
}
