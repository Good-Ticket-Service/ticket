package com.sparta.ticket.payment.application.port.payment;

import com.sparta.ticket.payment.application.dto.payment.ApprovalClaim;

/**
 * 커밋된 승인 실행권을 PG 처리에 전달한다. D16에 따라 승인 실행과 인증값 전달은 동기 호출을 유지한다.
 * 결과는 처리 뒤 최신 상태를 다시 읽어 얻는다.
 */
public interface PaymentDispatcher {

    /**
     * @param authToken 사용자 인증 결과. DB에 저장하지 않으므로 처리하는 쪽에 그대로 넘긴다
     */
    void dispatch(ApprovalClaim claim, String authToken);
}
