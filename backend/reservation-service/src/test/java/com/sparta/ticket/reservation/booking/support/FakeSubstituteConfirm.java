package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer;
import com.sparta.ticket.reservation.booking.application.port.SubstituteConfirmPort;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 대체 승인 대역. 받은 승인과 조회를 따로 기록하고, 주문마다 테스트가 정한 답을 차례로 준다. 정한 답이 다 떨어지면 받았다고 답한다.
 * 승인이 불릴 때 트랜잭션이 열려 있었는지 기록해 "원격 호출은 트랜잭션 밖" 규칙을 확인한다.
 */
public class FakeSubstituteConfirm implements SubstituteConfirmPort {

    public final List<PaymentConfirmRequest> received = new CopyOnWriteArrayList<>();
    public final List<PaymentConfirmRequest> lookedUp = new CopyOnWriteArrayList<>();
    public final List<Boolean> calledInTransaction = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Supplier<SubstituteAnswer>>> answers = new ConcurrentHashMap<>();
    private final Map<String, Deque<Supplier<SubstituteAnswer>>> lookupAnswers = new ConcurrentHashMap<>();
    private volatile CountDownLatch release = new CountDownLatch(0);

    public void reset() {
        received.clear();
        lookedUp.clear();
        calledInTransaction.clear();
        answers.clear();
        lookupAnswers.clear();
        release.countDown();
        release = new CountDownLatch(0);
    }

    public void answers(String orderId, SubstituteAnswer... inOrder) {
        for (SubstituteAnswer answer : inOrder) {
            queue(answers, orderId).add(() -> answer);
        }
    }

    public void lookupAnswers(String orderId, SubstituteAnswer... inOrder) {
        for (SubstituteAnswer answer : inOrder) {
            queue(lookupAnswers, orderId).add(() -> answer);
        }
    }

    /** 다음 승인 한 번은 응답을 받지 못한 것처럼 예외를 던진다. */
    public void failsOnce(String orderId) {
        queue(answers, orderId).add(() -> {
            throw new IllegalStateException("read timeout");
        });
    }

    /** release가 열릴 때까지 승인에 답하지 않는다. 요청은 받은 것으로 먼저 기록한다. */
    public void holdUntil(CountDownLatch release) {
        this.release = release;
    }

    public List<String> receivedOrderIds() {
        return received.stream().map(PaymentConfirmRequest::orderId).toList();
    }

    public List<String> lookedUpOrderIds() {
        return lookedUp.stream().map(PaymentConfirmRequest::orderId).toList();
    }

    @Override
    public SubstituteAnswer confirm(PaymentConfirmRequest request) {
        calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        received.add(request);
        await(release);
        return next(answers, request);
    }

    @Override
    public SubstituteAnswer lookup(PaymentConfirmRequest request) {
        lookedUp.add(request);
        return next(lookupAnswers, request);
    }

    private static SubstituteAnswer next(
            Map<String, Deque<Supplier<SubstituteAnswer>>> answers, PaymentConfirmRequest request) {
        Supplier<SubstituteAnswer> next = queue(answers, request.orderId()).poll();
        return next == null ? new SubstituteAnswer.Accepted() : next.get();
    }

    private static Deque<Supplier<SubstituteAnswer>> queue(
            Map<String, Deque<Supplier<SubstituteAnswer>>> answers, String orderId) {
        return answers.computeIfAbsent(orderId, key -> new ConcurrentLinkedDeque<>());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 대체 승인 응답을 풀지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
