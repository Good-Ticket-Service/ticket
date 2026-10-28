package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.port.pg.PgClient.PgApproveRequest;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** PG 호출에는 트랜잭션을 열지 않는다. 상태 커밋 실패 시 복구가 원거래를 조회한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessor {
    private final PaymentTransactions transactions;
    private final PgClientRegistry pgClients;

    public void process(Long paymentId, String authToken) {
        Payment payment;
        try {
            payment = transactions.markInProgress(paymentId, authToken).orElse(null);
        } catch (DataIntegrityViolationException e) {
            if (!isTransactionKeyConflict(e)) {
                throw e;
            }
            // Note: PostgreSQL 제약 위반 트랜잭션은 이미 실패했으므로 새 트랜잭션에서 종료한다.
            transactions.rejectInvalidToken(paymentId);
            return;
        }
        if (payment == null) {
            return;
        }
        PgResult result;
        try {
            result = pgClients
                    .get(payment.getPgProvider())
                    .approve(new PgApproveRequest(
                            payment.getOrderId(),
                            payment.getPgTransactionId(),
                            authToken,
                            payment.getAmount(),
                            payment.getUserId()));
            if (result == null) {
                result = new PgResult.Unknown("PG_EMPTY_RESULT");
            }
        } catch (RuntimeException e) {
            // Note: 예외 메시지에는 요청 인증값이 들어갈 수 있어 저장·로그에 넣지 않는다.
            result = new PgResult.Unknown("PG_CALL_EXCEPTION");
        }
        try {
            transactions.complete(paymentId, result);
        } catch (RuntimeException e) {
            log.error("승인 결과 저장 실패. PG 재호출 없이 복구 조회가 필요하다. paymentId={}", paymentId);
        }
    }

    private static boolean isTransactionKeyConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_p_payments_pg_transaction".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
