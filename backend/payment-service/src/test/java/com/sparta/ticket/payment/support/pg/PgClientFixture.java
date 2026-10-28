package com.sparta.ticket.payment.support.pg;

import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 결제 흐름 통합 테스트용 메모리 PG. 실제 HTTP는 어댑터 계약 테스트가 검증하므로, 여기서는 PG가 어떤 결과를 언제 돌려줄지만 정한다.
 * 응답을 차례로 쌓아 두고, 래치로 응답을 붙잡아 "준비 응답이 늦게 도착"하는 순서를 만든다. 테스트마다 {@link #reset()}한다.
 *
 * Note: 이 클래스는 PgClient를 직접 구현하지 않고 {@link #client()}로 내준다. PgClient 빈으로 등록하면 실제 PgClientRegistry가
 * 카카오 구현이 둘(어댑터, 픽스처)이라며 뜨지 못한다.
 */
public class PgClientFixture {

    private final Deque<Supplier<PgReadyResult>> readyResponses = new ArrayDeque<>();
    private final AtomicInteger readyCalls = new AtomicInteger();
    private volatile CountDownLatch readyGate;
    private volatile CountDownLatch readyEntered;
    private final AtomicInteger approveCalls = new AtomicInteger();
    private volatile Function<PgClient.PgApproveRequest, PgResult> approveResponse;
    private volatile boolean approvalAllowed = true;

    public synchronized void reset() {
        readyResponses.clear();
        readyCalls.set(0);
        readyGate = null;
        readyEntered = null;
        approveCalls.set(0);
        approveResponse = null;
        approvalAllowed = true;
    }

    public void approveReturns(Function<PgClient.PgApproveRequest, PgResult> response) {
        approveResponse = response;
    }

    public int approveCalls() {
        return approveCalls.get();
    }

    public boolean approvalAllowed() {
        return approvalAllowed;
    }

    public void approvalAllowed(boolean allowed) {
        approvalAllowed = allowed;
    }

    /** 다음 준비 호출들이 차례로 돌려줄 결과. 쌓인 결과가 없으면 성공(tid 자동 생성)이다. */
    public synchronized void readyReturns(Supplier<PgReadyResult> response) {
        readyResponses.add(response);
    }

    /** 준비 호출을 {@link #releaseReady()}까지 붙잡는다. 들어온 호출 수는 {@link #awaitReadyEntered(int)}로 기다린다. */
    public void holdReady(int expectedCalls) {
        readyGate = new CountDownLatch(1);
        readyEntered = new CountDownLatch(expectedCalls);
    }

    public void awaitReadyEntered(int calls) throws InterruptedException {
        if (!readyEntered.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("준비 호출이 " + calls + "번 들어오지 않았다");
        }
    }

    public void releaseReady() {
        readyGate.countDown();
    }

    public int readyCalls() {
        return readyCalls.get();
    }

    public PgClient client() {
        return client;
    }

    private final PgClient client = new PgClient() {

        @Override
        public PgProvider provider() {
            return PgProvider.KAKAO;
        }

        @Override
        public PgReadyResult ready(PgReadyRequest request) {
            return PgClientFixture.this.ready(request);
        }

        @Override
        public PgResult approve(PgApproveRequest request) {
            approveCalls.incrementAndGet();
            Function<PgApproveRequest, PgResult> response = approveResponse;
            return response == null
                    ? new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now())
                    : response.apply(request);
        }

        @Override
        public PgInquiry inquire(PgInquireRequest request) {
            throw new UnsupportedOperationException("Task 9에서 쓴다");
        }

        @Override
        public PgResult cancel(PgCancelRequest request) {
            throw new UnsupportedOperationException("Task 10에서 쓴다");
        }
    };

    private PgReadyResult ready(PgClient.PgReadyRequest request) {
        int call = readyCalls.incrementAndGet();
        Supplier<PgReadyResult> response = nextResponse();
        CountDownLatch gate = readyGate;
        if (gate != null) {
            readyEntered.countDown();
            awaitQuietly(gate);
        }
        if (response != null) {
            return response.get();
        }
        String tid = "T-" + request.orderId() + "-" + call;
        return new PgReadyResult.Ready(tid, "https://fake-kakao/checkout/" + tid);
    }

    private synchronized Supplier<PgReadyResult> nextResponse() {
        return readyResponses.poll();
    }

    private static void awaitQuietly(CountDownLatch gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
