package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentPort;
import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.booking.application.port.PreparedPayment;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 결제 대역. 테스트가 준비 응답 금액, 승인 결과, 조회 결과, 환불 결과, 응답 지연을 정한다.
 * 호출될 때 트랜잭션이 열려 있었는지 기록해 "원격 호출은 트랜잭션 밖" 규칙을 확인한다.
 */
public class FakePaymentPort implements PaymentPort {

    public final List<PaymentPrepareRequest> prepared = new CopyOnWriteArrayList<>();
    public final List<String> confirmedOrderIds = new CopyOnWriteArrayList<>();
    public final List<Long> confirmedAmounts = new CopyOnWriteArrayList<>();
    public final List<Boolean> calledInTransaction = new CopyOnWriteArrayList<>();
    public final List<String> lookedUpOrderIds = new CopyOnWriteArrayList<>();
    public final List<String> refundedOrderIds = new CopyOnWriteArrayList<>();
    public final List<Integer> activeConnectionsAtCall = new CopyOnWriteArrayList<>();
    private volatile IntSupplier activeConnections = () -> 0;
    private final Map<String, PaymentLookup> lookups = new ConcurrentHashMap<>();

    private volatile long discount;
    private volatile Supplier<ConfirmOutcome> confirmResult = ConfirmOutcome::succeeded;
    private volatile Duration lookupDelay = Duration.ZERO;
    private volatile Runnable beforePrepare = () -> {};
    private volatile Supplier<RefundOutcome> refundResult = RefundOutcome::accepted;

    public void reset() {
        prepared.clear();
        confirmedOrderIds.clear();
        confirmedAmounts.clear();
        calledInTransaction.clear();
        lookedUpOrderIds.clear();
        refundedOrderIds.clear();
        activeConnectionsAtCall.clear();
        lookups.clear();
        discount = 0;
        confirmResult = ConfirmOutcome::succeeded;
        lookupDelay = Duration.ZERO;
        beforePrepare = () -> {};
        refundResult = RefundOutcome::accepted;
    }

    /** 호출될 때 커넥션 풀에서 빌려 간 연결 수를 함께 기록한다. 원격 호출 중에 DB 연결을 잡고 있지 않은지 본다. */
    public void watchConnections(IntSupplier activeConnections) {
        this.activeConnections = activeConnections;
    }

    /** 준비 응답 금액을 요청 금액(totalPrice - discountAmount)보다 이만큼 낮춘다. booking이 응답 금액을 기록하는지 본다. */
    public void discount(long amount) {
        this.discount = amount;
    }

    /** 다음 준비 요청을 받기 직전에 한 번 실행한다. 준비 호출과 겹쳐 들어온 다른 요청을 흉내 낸다. */
    public void beforeNextPrepare(Runnable action) {
        this.beforePrepare = action;
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

    public void refundReturns(RefundOutcome outcome) {
        this.refundResult = () -> outcome;
    }

    public void refundAnswers(Supplier<RefundOutcome> answer) {
        this.refundResult = answer;
    }

    public void refundTimesOut() {
        this.refundResult = () -> {
            throw new IllegalStateException("read timeout");
        };
    }

    public void lookupDelay(Duration delay) {
        this.lookupDelay = delay;
    }

    @Override
    public PreparedPayment prepare(PaymentPrepareRequest request) {
        Runnable action = beforePrepare;
        beforePrepare = () -> {};
        action.run();
        record();
        prepared.add(request);
        return new PreparedPayment(request.totalPrice() - request.discountAmount() - discount, null);
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

    @Override
    public RefundOutcome refund(String orderId) {
        record();
        refundedOrderIds.add(orderId);
        return refundResult.get();
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
