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
        CANCELED,
        /** 등록은 커밋됐지만 PG별 자리나 PG 허가(서킷·Bulkhead)를 얻지 못했다. 호출자가 다른 경로로 넘길 수 있다(#417). */
        DEFER
    }

    private final MessageApprovalTransactions transactions;
    private final PaymentTransactions results;
    private final PgClientRegistry clients;
    private final Clock clock;
    private final Map<PgProvider, Semaphore> slots;
    private final long slotWaitNanos;

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
        slotWaitNanos = properties.getSlotWait().toNanos();
        slots = Map.of(
                PgProvider.TOSS,
                new Semaphore(properties.getPerProvider(), true),
                PgProvider.KAKAO,
                new Semaphore(properties.getPerProvider(), true));
    }

    public Outcome advance(MessageConfirmCommand command, BooleanSupplier owner) {
        return advance(new MessageApprovalAttempt(command), owner);
    }

    public Outcome advance(MessageApprovalAttempt attempt, BooleanSupplier owner) {
        return advance(attempt, owner, () -> {});
    }

    /**
     * {@code accepted}는 claim 커밋 직후 한 번 호출된다(#417). 이때부터 결과 책임은 DB의 IN_PROGRESS와 조회 복구(Recovery)가 지므로
     * 호출자는 메시지를 acknowledge할 수 있다. claim 뒤에는 소비권을 다시 보지 않고 PG까지 보낸다. 소비권을 잃었다고 보내지 않으면
     * 결제가 IN_PROGRESS로 남아 Recovery의 NOT_APPROVED로 끝난다. 같은 주문의 PG 호출은 claim 행 잠금으로 한 번이다.
     */
    public Outcome advance(MessageApprovalAttempt attempt, BooleanSupplier owner, Runnable accepted) {
        return advance(attempt, owner, accepted, false);
    }

    /**
     * {@code defer}이면 자리를 {@code slot-wait}만큼 기다려도 없거나 PG 허가가 없을 때 RETRY 대신 DEFER를 돌려준다.
     * 원본 Topic 레인이 PG별 retry Topic으로 넘길 때 쓴다(#417 B). 등록 TX는 DEFER 전에 이미 커밋돼 있다.
     */
    public Outcome advance(MessageApprovalAttempt attempt, BooleanSupplier owner, Runnable accepted, boolean defer) {
        return advance(attempt, owner, accepted, defer, true);
    }

    /**
     * HTTP 메시지 대체 승인(#422). 메시지 승인과 같은 처리(등록 → 시한 → PG별 자리·PG 허가 → claim → PG)를 호출 스레드에서 한다.
     * 자리를 기다리지 않는다. 자리·허가가 없으면 결제를 READY로 둔 채 DEFER를 돌려주고, 호출자가 503으로 답한다.
     */
    public Outcome approveNow(MessageConfirmCommand command) {
        return advance(new MessageApprovalAttempt(command), () -> true, () -> {}, true, false);
    }

    private Outcome advance(
            MessageApprovalAttempt attempt,
            BooleanSupplier owner,
            Runnable accepted,
            boolean defer,
            boolean waitForSlot) {
        if (!owner.getAsBoolean()) return Outcome.CANCELED;
        var command = attempt.command();
        var registration = attempt.registration(transactions, clock);
        if (registration.completed()) return Outcome.COMPLETE;
        Semaphore slot = slots.get(registration.provider());
        if (!(waitForSlot ? acquire(slot) : slot.tryAcquire()))
            return unavailable(attempt, registration.provider(), defer);
        try {
            var permit = clients.get(registration.provider()).reserveApproval();
            if (permit.isEmpty()) return unavailable(attempt, registration.provider(), defer);
            try (var approval = permit.get()) {
                if (!owner.getAsBoolean()) return Outcome.CANCELED;
                var claimed = transactions.claim(command);
                if (claimed.isEmpty()) return Outcome.COMPLETE;
                var claim = claimed.get();
                if (claim.provider() != registration.provider())
                    throw new IllegalStateException("승인 실행권의 PG가 등록 정보와 다르다");
                accepted.run();
                if (!transactions.maySend(command)) return Outcome.COMPLETE;
                // Note: DB 재검사와 실제 송신 사이의 시계 진행도 막는다. 이미 claim은 영속 복구 대상이다.
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

    /**
     * 모든 레인이 자리를 {@code slot-wait}만큼 공정(FIFO) 대기한다(#417). 원본 레인만 기다리고 retry 레인은 즉시 시도만 하면,
     * 새 레코드가 먼저 넘겨진(더 오래 기다린) 레코드보다 자리를 먼저 가져가 넘겨진 레코드의 꼬리 지연이 커진다(측정 p95 9 → 19초).
     */
    private boolean acquire(Semaphore slot) {
        if (slotWaitNanos <= 0) return slot.tryAcquire();
        try {
            return slot.tryAcquire(slotWaitNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Outcome unavailable(MessageApprovalAttempt attempt, PgProvider provider, boolean defer) {
        if (!defer) return Outcome.RETRY;
        attempt.deferTo(provider);
        return Outcome.DEFER;
    }
}
