package com.sparta.ticket.payment.application.port.pg;

import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.pg.PgResult;

/**
 * PG 하나를 부르는 포트. 구현(토스·카카오 어댑터)은 infrastructure에 있고, application은 이 인터페이스만 안다.
 * 승인·취소 구현은 자동 재시도를 하지 않는다. 재시도는 복구 잡이 결정한다.
 */
public interface PgClient {

    PgProvider provider();

    PgReadyResult ready(PgReadyRequest request);

    PgResult approve(PgApproveRequest request);

    PgInquiry inquire(PgInquireRequest request);

    /**
     * 같은 결제에 다시 불러도 안전해야 한다. 토스는 멱등 키, 카카오는 먼저 조회해 이미 취소됐으면 성공으로 돌려준다.
     * 응답이 "이미 취소됨"이면 다시 조회해 성공으로 확정한다.
     */
    PgResult cancel(PgCancelRequest request);

    /** @param userId 결제한 회원. 카카오는 준비·승인에 같은 partner_user_id를 보내야 한다. 토스는 쓰지 않는다 */
    record PgReadyRequest(String orderId, Long userId, int amount, String itemName) {}

    /**
     * @param transactionId 카카오 tid 또는 토스 paymentKey
     * @param authToken 사용자 인증 결과. 카카오 pg_token, 토스는 paymentKey와 같다
     * @param userId 결제한 회원. 카카오 partner_user_id로 보낸다
     */
    record PgApproveRequest(String orderId, String transactionId, String authToken, int amount, Long userId) {}

    record PgInquireRequest(String orderId, String transactionId) {}

    record PgCancelRequest(String orderId, String transactionId, int amount, String idempotencyKey, String reason) {}
}
