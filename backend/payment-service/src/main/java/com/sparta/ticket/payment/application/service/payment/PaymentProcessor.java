package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.ApprovalClaim;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgApproveRequest;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** PG 호출에는 트랜잭션을 열지 않는다. 상태 커밋 실패 시 복구가 원거래를 조회한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessor {
    private final PaymentTransactions transactions;
    private final PgClientRegistry pgClients;

    public void process(ApprovalClaim claim, String authToken) {
        PgResult result;
        try {
            result = pgClients
                    .get(claim.provider())
                    .approve(new PgApproveRequest(
                            claim.orderId(), claim.transactionId(), authToken, claim.amount(), claim.userId()));
            if (result == null) {
                result = new PgResult.Unknown("PG_EMPTY_RESULT");
            }
        } catch (RuntimeException e) {
            // Note: 예외 메시지에는 요청 인증값이 들어갈 수 있어 저장·로그에 넣지 않는다.
            result = new PgResult.Unknown("PG_CALL_EXCEPTION");
        }
        try {
            transactions.complete(claim.paymentId(), result);
        } catch (RuntimeException e) {
            log.error("승인 결과 저장 실패. PG 재호출 없이 복구 조회가 필요하다. paymentId={}", claim.paymentId());
        }
    }
}
