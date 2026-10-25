package com.sparta.ticket.reservation.support;

import com.sparta.ticket.reservation.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.application.port.PaymentLookup;
import com.sparta.ticket.reservation.application.port.PaymentPort;
import com.sparta.ticket.reservation.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.application.port.PreparedPayment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 결제 대역. 테스트가 준비 할인액, 승인 결과, 조회 결과, 응답 지연을 정한다.
 * 호출될 때 트랜잭션이 열려 있었는지 기록해 "원격 호출은 트랜잭션 밖" 규칙을 확인한다.
 */
public class FakePaymentPort implements PaymentPort {

    public final List<PaymentPrepareRequest> prepared = new CopyOnWriteArrayList<>();
    public final List<String> confirmedOrderIds = new CopyOnWriteArrayList<>();
    public final List<Long> confirmedAmounts = new CopyOnWriteArrayList<>();
    public final List<Boolean> calledInTransaction = new CopyOnWriteArrayList<>();
    public final List<String> lookedUpOrderIds = new CopyOnWriteArrayList<>();
    public final List<Integer> activeConnectionsAtCall = new CopyOnWriteArrayList<>();
    private volatile IntSupplier activeConnections = () -> 0;
    private final Map<String, PaymentLookup> lookups = new ConcurrentHashMap<>();

    private volatile long discount;
    private volatile Supplier<ConfirmOutcome> confirmResult = ConfirmOutcome::succeeded;
    private volatile Duration lookupDelay = Duration.ZERO;

    public void reset() {
        prepared.clear();
        confirmedOrderIds.clear();
        confirmedAmounts.clear();
        calledInTransaction.clear();
        lookedUpOrderIds.clear();
        activeConnectionsAtCall.clear();
        lookups.clear();
        discount = 0;
        confirmResult = ConfirmOutcome::succeeded;
        lookupDelay = Duration.ZERO;
    }

    /** 호출될 때 커넥션 풀에서 빌려 간 연결 수를 함께 기록한다. 원격 호출 중에 DB 연결을 잡고 있지 않은지 본다. */
    public void watchConnections(IntSupplier activeConnections) {
        this.activeConnections = activeConnections;
    }

    public void discount(long amount) {
        this.discount = amount;
    }

    public void confirmReturns(ConfirmOutcome outcome) {
        this.confirmResult = () -> outcome;
    }

    public void confirmAnswers(Supplier<ConfirmOutcome> answer) {
        this.confirmResult = answer;
    }

    public void confirmTimesOut() {
        this.confirmResult = () -> {
            throw new IllegalStateException("read timeout");
        };
    }

    public void lookupReturns(String orderId, PaymentLookup lookup) {
        lookups.put(orderId, lookup);
    }

    public void lookupDelay(Duration delay) {
        this.lookupDelay = delay;
    }

    @Override
    public PreparedPayment prepare(PaymentPrepareRequest request) {
        record();
        prepared.add(request);
        return new PreparedPayment(request.baseAmount() - discount, null);
    }

    @Override
    public ConfirmOutcome confirm(String orderId, String pgAuthToken, long amount) {
        record();
        confirmedOrderIds.add(orderId);
        confirmedAmounts.add(amount);
        return confirmResult.get();
    }

    @Override
    public PaymentLookup find(String orderId) {
        record();
        lookedUpOrderIds.add(orderId);
        sleep(lookupDelay);
        return lookups.getOrDefault(orderId, PaymentLookup.of(PaymentLookup.Status.IN_PROGRESS));
    }

    private void record() {
        calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        activeConnectionsAtCall.add(activeConnections.getAsInt());
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
