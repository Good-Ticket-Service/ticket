package com.sparta.ticket.reservation.booking.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.port.PaymentCallGuard.Call;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 통합 테스트로 재기 어려운 조건(연 뒤 다시 시험, 동시 호출 자리)을 작은 설정으로 확인한다. */
class Resilience4jPaymentCallGuardTest {

    private static final int MAX_CONCURRENT = 3;
    private static final int MINIMUM_CALLS = 4;
    /** 다시 연 뒤 단정할 때까지 이 시간이 지나면 시험 호출을 받아 버린다. 바쁜 빌드 장비에서도 넉넉하게 둔다. */
    private static final Duration OPEN = Duration.ofSeconds(1);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Resilience4jPaymentCallGuard guard = guard(true);

    private Resilience4jPaymentCallGuard guard(boolean enabled) {
        return new Resilience4jPaymentCallGuard(
                new PaymentGuardProperties(
                        enabled, MAX_CONCURRENT, 10, MINIMUM_CALLS, 50, OPEN, 2, Duration.ofMinutes(1)),
                meters);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    private void unanswered(int n) {
        for (int i = 0; i < n; i++) {
            guard.start().unanswered(new IllegalStateException("read timeout"));
        }
    }

    private List<Call> inFlight(int n) {
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(guard.start());
        }
        return calls;
    }

    private static void assertBlocked(Resilience4jPaymentCallGuard guard) {
        assertThatThrownBy(guard::start)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("동시에 기다리는 호출이 한도에 차면 다음 호출은 기다리지 않고 거절하고, 하나가 끝나면 다시 받는다")
    void limitsConcurrentCalls() {
        List<Call> calls = inFlight(MAX_CONCURRENT);

        assertBlocked(guard);

        calls.get(0).answered();
        guard.start().answered();
    }

    @Test
    @DisplayName("응답이 없든 부르지 않았든 끝난 호출은 자리를 돌려준다")
    void everyEndingFreesSlot() {
        List<Call> calls = inFlight(MAX_CONCURRENT);
        calls.get(0).unanswered(new IllegalStateException("read timeout"));
        calls.get(1).skipped();
        calls.get(2).answered();

        inFlight(MAX_CONCURRENT);
    }

    @Test
    @DisplayName("동시 호출 한도로 거절한 것은 응답 없음으로 세지 않는다")
    void bulkheadRejectionDoesNotOpenCircuit() {
        List<Call> calls = inFlight(MAX_CONCURRENT);
        for (int i = 0; i < MINIMUM_CALLS * 3; i++) {
            assertBlocked(guard);
        }
        calls.forEach(Call::answered);

        guard.start().answered();
    }

    @Test
    @DisplayName("느려도 답이 오면 회로를 열지 않는다. 느릴 때는 동시 호출 한도만 막는다")
    void slowAnswersKeepCircuitClosed() throws InterruptedException {
        for (int i = 0; i < MINIMUM_CALLS * 2; i++) {
            Call call = guard.start();
            sleep(Duration.ofMillis(20));
            call.answered();
        }

        guard.start().answered();
    }

    @Test
    @DisplayName("응답 없음이 이어지면 연다")
    void opensOnUnanswered() {
        unanswered(MINIMUM_CALLS);

        assertBlocked(guard);
    }

    @Test
    @DisplayName("payment를 부르지 않고 끝낸 호출은 판단에 넣지 않는다")
    void skippedCallsDoNotCount() {
        for (int i = 0; i < MINIMUM_CALLS * 3; i++) {
            guard.start().skipped();
        }
        unanswered(MINIMUM_CALLS - 1);

        guard.start().answered();
    }

    @Test
    @DisplayName("연 뒤 정한 시간이 지나면 몇 번만 보내 보고, 답이 오면 닫는다")
    void closesAfterTrialCallsAnswer() throws InterruptedException {
        unanswered(MINIMUM_CALLS);
        assertBlocked(guard);

        sleep(OPEN.plusMillis(50));
        guard.start().answered();
        guard.start().answered();

        for (int i = 0; i < MINIMUM_CALLS; i++) {
            guard.start().answered();
        }
    }

    @Test
    @DisplayName("시험 호출에도 답이 없으면 다시 연다")
    void reopensWhenTrialFails() throws InterruptedException {
        unanswered(MINIMUM_CALLS);
        sleep(OPEN.plusMillis(50));

        unanswered(2);

        assertBlocked(guard);
    }

    @Test
    @DisplayName("한 호출의 결과는 한 번만 센다")
    void recordsOnce() {
        for (int i = 0; i < MINIMUM_CALLS; i++) {
            Call call = guard.start();
            call.answered();
            call.unanswered(new IllegalStateException("두 번째 기록은 무시된다"));
        }

        inFlight(MAX_CONCURRENT);
    }

    @Test
    @DisplayName("끄면 응답 없는 호출이 이어지거나 동시 호출이 많아도 막지 않는다")
    void disabledNeverBlocks() {
        Resilience4jPaymentCallGuard disabled = guard(false);

        for (int i = 0; i < MINIMUM_CALLS * 3; i++) {
            disabled.start().unanswered(new IllegalStateException("read timeout"));
        }
        for (int i = 0; i < MAX_CONCURRENT * 3; i++) {
            disabled.start();
        }
    }

    @Test
    @DisplayName("상태, 막은 호출 수, 남은 동시 호출 자리를 지표로 내보낸다")
    void exportsMetrics() {
        inFlight(MAX_CONCURRENT).forEach(call -> call.unanswered(new IllegalStateException("read timeout")));
        unanswered(MINIMUM_CALLS - MAX_CONCURRENT);
        assertBlocked(guard);

        assertThat(meters.get("resilience4j.circuitbreaker.not.permitted.calls")
                        .tag("name", "payment")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("resilience4j.circuitbreaker.state")
                        .tags("name", "payment", "state", "open")
                        .gauge()
                        .value())
                .isEqualTo(1);
        assertThat(meters.get("resilience4j.bulkhead.available.concurrent.calls")
                        .tag("name", "payment")
                        .gauge()
                        .value())
                .isEqualTo(MAX_CONCURRENT);
    }
}
