package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgBulkheadTest {

    private static final PgCall OK = new PgCall.Responded(200, "{}");

    private static PgBulkhead bulkhead(boolean enabled, int limit, SimpleMeterRegistry meters) {
        return new PgBulkhead(new PgBulkheadProperties(enabled, limit, limit, Duration.ofMillis(30)), meters);
    }

    /** 첫 호출이 자리를 잡고 latch가 풀릴 때까지 붙잡고 있게 한다. */
    private static CompletableFuture<PgCall> occupy(PgBulkhead bulkhead, String provider, CountDownLatch release)
            throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<PgCall> first = CompletableFuture.supplyAsync(() -> bulkhead.call(provider, "confirm", () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OK;
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        return first;
    }

    @Test
    @DisplayName("꺼져 있으면 상한 없이 그대로 부른다")
    void disabledPassesThrough() throws Exception {
        PgBulkhead bulkhead = bulkhead(false, 1, new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<PgCall> first = occupy(bulkhead, "TOSS", release);
        assertThat(bulkhead.call("TOSS", "confirm", () -> OK)).isEqualTo(OK);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(OK);
    }

    @Test
    @DisplayName("상한이 차면 대기 시간 뒤 PG를 부르지 않고 자체 한도 미전송으로 돌려준다")
    void rejectsWithoutCallingWhenFull() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PgBulkhead bulkhead = bulkhead(true, 1, meters);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<PgCall> first = occupy(bulkhead, "TOSS", release);
        AtomicInteger called = new AtomicInteger();

        PgCall rejected = bulkhead.call("TOSS", "confirm", () -> {
            called.incrementAndGet();
            return OK;
        });

        assertThat(called).hasValue(0);
        assertThat(rejected).isInstanceOf(PgCall.NotSent.class);
        assertThat(((PgCall.NotSent) rejected).localLimit()).isTrue();
        assertThat(PgHttp.transportResult(rejected))
                .get()
                .isInstanceOf(PgResult.Rejected.class)
                .extracting("code")
                .isEqualTo("PG_BUSY");
        assertThat(meters.get("payment.pg.bulkhead.wait")
                        .tag("outcome", "rejected")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("payment.pg.bulkhead.in_use")
                        .tag("provider", "TOSS")
                        .gauge()
                        .value())
                .isEqualTo(1);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(OK);
    }

    @Test
    @DisplayName("호출이 예외로 끝나도 자리를 돌려준다")
    void releasesSlotOnException() {
        PgBulkhead bulkhead = bulkhead(true, 1, new SimpleMeterRegistry());
        assertThatThrownBy(() -> bulkhead.call("KAKAO", "approve", () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.call("KAKAO", "approve", () -> OK)).isEqualTo(OK);
    }

    @Test
    @DisplayName("한 PG의 상한이 차도 다른 PG 호출은 막지 않는다")
    void providersAreIsolated() throws Exception {
        PgBulkhead bulkhead = bulkhead(true, 1, new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<PgCall> toss = occupy(bulkhead, "TOSS", release);
        assertThat(bulkhead.call("KAKAO", "approve", () -> OK)).isEqualTo(OK);
        release.countDown();
        assertThat(toss.get(5, TimeUnit.SECONDS)).isEqualTo(OK);
    }

    @Test
    @DisplayName("상한·대기 시간이 잘못되면 뜨지 않는다")
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new PgBulkheadProperties(true, 0, 1, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgBulkheadProperties(true, 1, 1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
