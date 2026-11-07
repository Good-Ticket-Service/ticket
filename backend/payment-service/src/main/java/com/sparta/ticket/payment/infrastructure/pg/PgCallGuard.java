package com.sparta.ticket.payment.infrastructure.pg;

import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * PG 호출 보호. 서킷 브레이커가 멈춘 PG를 막고, 동시 호출 상한이 서킷이 열리기 전 감지 구간에 한 PG가 요청 스레드를 다 쓰지 못하게 한다.
 * 어느 쪽이든 막으면 PG로 보내지 않고 {@link PgCall.NotSent}(결제 서비스 자체 한도)를 돌려준다.
 *
 * Note: 보낸 뒤의 결과는 바꾸지 않는다. 서킷에는 실제로 보낸 호출만 기록한다.
 */
@Component
public class PgCallGuard {

    private final PgCircuitBreakers breakers;
    private final PgBulkhead bulkhead;

    public PgCallGuard(PgCircuitBreakers breakers, PgBulkhead bulkhead) {
        this.breakers = breakers;
        this.bulkhead = bulkhead;
    }

    public PgCall call(String provider, String operation, Supplier<PgCall> call) {
        CircuitBreaker breaker = breakers == null ? null : breakers.get(provider);
        if (breaker == null) {
            return bulkhead == null ? call.get() : bulkhead.call(provider, operation, call);
        }
        if (!breaker.tryAcquirePermission()) {
            return new PgCall.NotSent("PG 서킷 열림", false, true);
        }
        long started = System.nanoTime();
        PgCall result;
        try {
            result = bulkhead == null ? call.get() : bulkhead.call(provider, operation, call);
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, e);
            throw e;
        }
        long elapsed = System.nanoTime() - started;
        if (result instanceof PgCall.NotSent notSent && notSent.localLimit()) {
            // Note: 동시 호출 상한으로 보내지 않았다. PG 상태와 무관하므로 판단에 넣지 않고 허가만 돌려준다.
            breaker.releasePermission();
        } else if (unavailable(result)) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, new PgUnavailable());
        } else {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        }
        return result;
    }

    /** 응답을 못 받았거나(타임아웃·끊김) 연결하지 못했거나 PG가 5xx를 줬다. 업무 거절과 429는 PG가 응답한 것이라 세지 않는다. */
    private static boolean unavailable(PgCall result) {
        if (result instanceof PgCall.SentButFailed || result instanceof PgCall.NotSent) return true;
        return result instanceof PgCall.Responded responded && responded.status() >= 500;
    }

    /** 서킷 실패 기록용 표지. 스택은 만들지 않는다. */
    private static final class PgUnavailable extends RuntimeException {
        PgUnavailable() {
            super("PG unavailable", null, false, false);
        }
    }
}
