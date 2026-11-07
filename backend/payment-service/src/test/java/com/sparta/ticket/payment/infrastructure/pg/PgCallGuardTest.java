package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgCallGuardTest {

    private static final PgCall OK = new PgCall.Responded(200, "{}");
    private static final PgCall TIMEOUT = new PgCall.SentButFailed("read timeout");

    // Note: 판단 창 4건, 최소 4건, 실패율 50%, 느린 호출 100ms·50%, 열림 200ms, 시험 호출 2건으로 줄여 빠르게 본다.
    private static PgCallGuard guard() {
        PgCircuitBreakerProperties properties = new PgCircuitBreakerProperties(
                true, 50, Duration.ofMillis(100), 50, 4, 4, Duration.ofMillis(200), 2, Duration.ofSeconds(5));
        PgBulkhead bulkhead =
                new PgBulkhead(new PgBulkheadProperties(false, 1, 1, Duration.ofMillis(10)), (MeterRegistry) null);
        return new PgCallGuard(new PgCircuitBreakers(properties, (MeterRegistry) null), bulkhead);
    }

    private static Supplier<PgCall> counting(AtomicInteger sent, PgCall result) {
        return () -> {
            sent.incrementAndGet();
            return result;
        };
    }

    @Test
    @DisplayName("응답을 못 받은 호출이 기준을 넘으면 서킷이 열려, 그 PG로는 보내지 않고 서킷 열림 거절로 돌려준다")
    void opensOnFailuresAndStopsSending() {
        PgCallGuard guard = guard();
        AtomicInteger sent = new AtomicInteger();
        for (int i = 0; i < 4; i++) guard.call("TOSS", "approve", counting(sent, TIMEOUT));

        PgCall blocked = guard.call("TOSS", "approve", counting(sent, OK));

        assertThat(sent).hasValue(4);
        assertThat(blocked).isInstanceOf(PgCall.NotSent.class);
        assertThat(((PgCall.NotSent) blocked).circuitOpen()).isTrue();
        // Note: 다른 PG는 영향을 받지 않는다.
        assertThat(guard.call("KAKAO", "approve", counting(sent, OK))).isEqualTo(OK);
    }

    @Test
    @DisplayName("업무 거절(4xx)과 기준보다 빠른 응답은 실패로 세지 않아 서킷이 열리지 않는다")
    void businessRejectionsDoNotOpen() {
        PgCallGuard guard = guard();
        AtomicInteger sent = new AtomicInteger();
        for (int i = 0; i < 8; i++) guard.call("TOSS", "approve", counting(sent, new PgCall.Responded(400, "{}")));

        assertThat(guard.call("TOSS", "approve", counting(sent, OK))).isEqualTo(OK);
        assertThat(sent).hasValue(9);
    }

    @Test
    @DisplayName("기준 시간을 넘는 느린 응답이 비율을 넘으면 성공했더라도 서킷이 열린다")
    void opensOnSlowCalls() {
        PgCallGuard guard = guard();
        AtomicInteger sent = new AtomicInteger();
        Supplier<PgCall> slow = () -> {
            sent.incrementAndGet();
            sleep(130);
            return OK;
        };
        for (int i = 0; i < 4; i++) guard.call("TOSS", "approve", slow);

        assertThat(guard.call("TOSS", "approve", counting(sent, OK))).isInstanceOf(PgCall.NotSent.class);
        assertThat(sent).hasValue(4);
    }

    @Test
    @DisplayName("열림 시간이 지나면 시험 호출을 보내고, 성공하면 다시 닫혀 정상 호출한다")
    void closesAfterSuccessfulTrialCalls() {
        PgCallGuard guard = guard();
        AtomicInteger sent = new AtomicInteger();
        for (int i = 0; i < 4; i++) guard.call("TOSS", "approve", counting(sent, TIMEOUT));
        sleep(250);

        assertThat(guard.call("TOSS", "approve", counting(sent, OK))).isEqualTo(OK);
        assertThat(guard.call("TOSS", "approve", counting(sent, OK))).isEqualTo(OK);
        assertThat(guard.call("TOSS", "approve", counting(sent, OK))).isEqualTo(OK);
        assertThat(sent).hasValue(7);
    }

    @Test
    @DisplayName("동시 호출 상한으로 보내지 않은 호출은 서킷 판단에 넣지 않는다")
    void bulkheadRejectionIsNotRecorded() {
        PgCircuitBreakerProperties properties = new PgCircuitBreakerProperties(
                true, 50, Duration.ofMillis(100), 50, 4, 4, Duration.ofMillis(200), 2, Duration.ofSeconds(5));
        PgCircuitBreakers breakers = new PgCircuitBreakers(properties, (MeterRegistry) null);
        PgBulkhead full =
                new PgBulkhead(new PgBulkheadProperties(true, 1, 1, Duration.ofMillis(10)), (MeterRegistry) null) {
                    @Override
                    public PgCall call(String provider, String operation, Supplier<PgCall> call) {
                        return new PgCall.NotSent("full", true);
                    }
                };
        PgCallGuard guard = new PgCallGuard(breakers, full);
        for (int i = 0; i < 8; i++) guard.call("TOSS", "approve", () -> OK);

        assertThat(breakers.get("TOSS").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
