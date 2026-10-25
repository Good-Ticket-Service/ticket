package com.sparta.ticket.payment.application.port.payment;

import com.sparta.ticket.payment.application.port.pg.PgClient;

/**
 * 승인 요청을 PG 승인 처리로 넘기는 포트. "어느 PG"는 {@link PgClient}가, "언제"는 이 포트가 정한다.
 *
 * Note: 결과를 반환하지 않는다(void). Inline 구현은 요청 스레드에서 바로 처리하고, Kafka 구현은 토픽에 넣고 바로 돌아온다.
 * 결과를 반환하게 만들면 Kafka 구현이 불가능해 두 방식을 설정만으로 바꿀 수 없다. 결과는 처리 뒤 최신 상태를 다시 읽어 얻는다.
 */
public interface PaymentDispatcher {

    /**
     * @param authToken 사용자 인증 결과. DB에 저장하지 않으므로 처리하는 쪽에 그대로 넘긴다
     */
    void dispatch(Long paymentId, String authToken);
}
