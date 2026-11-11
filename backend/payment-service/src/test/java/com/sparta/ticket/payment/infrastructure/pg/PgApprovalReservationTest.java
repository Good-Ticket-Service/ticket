package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PgApprovalReservationTest {
    @Test
    void unusedHalfOpenPermissionAndBulkheadSlotAreReturnedAndPermitIsSingleUse() {
        var breakers = new PgCircuitBreakers(
                new PgCircuitBreakerProperties(
                        true, 50, Duration.ofSeconds(5), 80, 10, 1, Duration.ofSeconds(10), 1, Duration.ofSeconds(30)),
                (MeterRegistry) null);
        var breaker = breakers.get("TOSS");
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        var bulkhead = new PgBulkhead(new PgBulkheadProperties(true, 1, 1, Duration.ZERO), (MeterRegistry) null);
        var guard = new PgCallGuard(breakers, bulkhead);
        var unused = guard.reserve("TOSS").orElseThrow();
        assertThat(guard.reserve("TOSS")).isEmpty();
        unused.close();
        unused.close();
        try (var permit = guard.reserve("TOSS").orElseThrow()) {
            assertThat(permit.call(() -> new PgCall.Responded(200, "{}"))).isInstanceOf(PgCall.Responded.class);
            assertThatThrownBy(() -> permit.call(() -> new PgCall.Responded(200, "{}")))
                    .isInstanceOf(IllegalStateException.class);
        }
        // Note: half-open 성공 후 CLOSED 전환 때 통계 창이 초기화된다. 허가 반환은 상태 전환으로 확인한다.
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        try (var available = guard.reserve("TOSS").orElseThrow()) {
            assertThat(available).isNotNull();
        }
    }
}
