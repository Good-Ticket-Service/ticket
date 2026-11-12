package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 리스너 스레드·DB 트랜잭션 밖에서만 PG를 호출한다. 미전송은 READY로 대기한다. */
@Service
@Slf4j
public class MessageApprovalService {
    public enum Outcome {
        COMPLETE,
        RETRY,
        CANCELED
    }

    private final MessageApprovalTransactions transactions;
    private final PaymentTransactions results;
    private final PgClientRegistry clients;
    private final Clock clock;
    private final Map<PgProvider, Semaphore> slots;

    public MessageApprovalService(
            MessageApprovalTransactions transactions,
            PaymentTransactions results,
            PgClientRegistry clients,
            Clock clock,
            MessageApprovalProperties properties) {
        properties.validate();
        this.transactions = transactions;
        this.results = results;
        this.clients = clients;
        this.clock = clock;
        slots = Map.of(
                PgProvider.TOSS,
                new Semaphore(properties.getPerProvider()),
                PgProvider.KAKAO,
                new Semaphore(properties.getPerProvider()));
    }

    public Outcome advance(MessageConfirmCommand command, BooleanSupplier owner) {
        return advance(new MessageApprovalAttempt(command), owner);
    }

    public Outcome advance(MessageApprovalAttempt attempt, BooleanSupplier owner) {
        if (!owner.getAsBoolean()) return Outcome.CANCELED;
        var command = attempt.command();
        var registration = attempt.registration(transactions, clock);
        if (registration.completed()) return Outcome.COMPLETE;
        Semaphore slot = slots.get(registration.provider());
        if (!slot.tryAcquire()) return Outcome.RETRY;
        try {
            var permit = clients.get(registration.provider()).reserveApproval();
            if (permit.isEmpty()) return Outcome.RETRY;
            try (var approval = permit.get()) {
                if (!owner.getAsBoolean()) return Outcome.CANCELED;
                var claimed = transactions.claim(command);
                if (claimed.isEmpty()) return Outcome.COMPLETE;
                var claim = claimed.get();
                if (claim.provider() != registration.provider())
                    throw new IllegalStateException("승인 실행권의 PG가 등록 정보와 다르다");
                if (!owner.getAsBoolean()) return Outcome.CANCELED;
                if (!transactions.maySend(command)) return Outcome.COMPLETE;
                // Note: DB 재검사와 실제 송신 사이의 시계 진행도 막는다. 이미 claim은 영속 복구 대상이다.
                if (!owner.getAsBoolean()) return Outcome.CANCELED;
                if (!LocalDateTime.now(clock).isBefore(command.confirmBefore())) {
                    transactions.maySend(command);
                    return Outcome.COMPLETE;
                }
                PgResult result;
                try {
                    result = approval.approve(new PgClient.PgApproveRequest(
                            claim.orderId(),
                            claim.transactionId(),
                            command.pgAuthToken(),
                            claim.amount(),
                            claim.userId()));
                    if (result == null) result = new PgResult.Unknown("PG_EMPTY_RESULT");
                } catch (RuntimeException exception) {
                    result = new PgResult.Unknown("PG_CALL_EXCEPTION");
                }
                try {
                    results.complete(claim.paymentId(), result);
                } catch (RuntimeException exception) {
                    // Note: 원문 오류와 인증값은 남기지 않는다. DB 인수 증거가 없으면 예외가 나가 ACK하지 않는다.
                    log.error("메시지 승인 결과 저장 실패. 원거래 조회 복구를 확인한다. paymentId={}", claim.paymentId());
                }
                transactions.confirmRecovery(command);
                return Outcome.COMPLETE;
            }
        } finally {
            slot.release();
        }
    }
}
