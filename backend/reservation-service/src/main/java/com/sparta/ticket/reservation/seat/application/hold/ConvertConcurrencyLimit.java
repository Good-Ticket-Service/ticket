package com.sparta.ticket.reservation.seat.application.hold;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 좌석 선택 완료 동시 처리 상한(#459). 상한에 닿으면 기다리지 않고 바로 거절한다.
 *
 * 왜 두나: REDIS 모드는 예약 쓰기를 좌석 선택 완료로 미룬다. 대기열 없이 오픈 직후 선점이 몰리면 5\~15초 뒤 좌석 선택 완료가 한꺼번에
 * 몰려(측정: 평소의 약 3.5배) 요청 스레드를 채우고, 같은 서버의 선점·좌석 조회까지 늦춘다. 좌석 선택 완료를 누른 회원은 이미 Redis에
 * 좌석을 잡아 두었으므로(유지 10분) 몇 초 늦춰도 좌석을 잃지 않는다. 그래서 이 요청만 상한을 두어 급한 선점·조회에 스레드를 남긴다.
 *
 * Retry-After: 거절당한 회원은 Retry-After마다 다시 오므로, 직전 창의 초당 거절 수 × 알려 준 Retry-After가 기다리는 회원 수다. 그 수를
 * 직전 창에서 초당 끝낸 수로 나눈 만큼 기다리게 해 다시 오는 요청이 처리할 수 있는 수에 가깝게 한다(예매 AdmissionGate와 같은 계산).
 * 고정 간격이면 기다리는 회원이 많을수록 거절만 되풀이한다.
 *
 * Note: 상한에 걸린 요청을 서버에서 기다리게 하면 그동안 요청 스레드를 쥐어 효과가 없다. 바로 503과 Retry-After로 돌려보낸다.
 * Note: 창(1초)마다 한 번, 거절하는 요청 스레드가 다시 계산한다. 서버가 여러 대면 대마다 자기 몫으로 계산한다.
 */
@Component
@EnableConfigurationProperties(ConvertLimitProperties.class)
public class ConvertConcurrencyLimit {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicLong rejectedInWindow = new AtomicLong();
    private final AtomicLong finishedInWindow = new AtomicLong();
    private final Duration shortest;
    private final Duration longest;
    private volatile int limit;
    private volatile Duration retryAfter;
    private long windowStart = System.nanoTime();

    public ConvertConcurrencyLimit(ConvertLimitProperties properties, MeterRegistry meters) {
        this.limit = properties.maxConcurrent();
        this.shortest = properties.retryAfter();
        this.longest = properties.maxRetryAfter();
        this.retryAfter = shortest;
        Gauge.builder("reservation.seat.hold.convert.in_use", inUse, AtomicInteger::get)
                .register(meters);
        Gauge.builder("reservation.seat.hold.convert.limit", this, l -> l.limit).register(meters);
        Gauge.builder("reservation.seat.hold.convert.retry_after", this, l -> l.retryAfter.toSeconds())
                .register(meters);
    }

    /** 자리를 얻으면 true. 반드시 {@link #exit()}로 돌려준다. 상한이 0이면 늘 true다. */
    public boolean tryEnter() {
        int max = limit;
        if (max <= 0) {
            inUse.incrementAndGet();
            return true;
        }
        while (true) {
            int current = inUse.get();
            if (current >= max) {
                rejectedInWindow.incrementAndGet();
                return false;
            }
            if (inUse.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void exit() {
        inUse.decrementAndGet();
        finishedInWindow.incrementAndGet();
    }

    /** 지금 거절에 실을 Retry-After. 창이 지났으면 다시 계산한다. */
    public Duration retryAfter() {
        long now = System.nanoTime();
        synchronized (this) {
            long elapsed = now - windowStart;
            if (elapsed >= WINDOW_NANOS) {
                double seconds = elapsed / 1e9;
                double waiting = rejectedInWindow.getAndSet(0) / seconds * retryAfter.toSeconds();
                double finishedPerSecond = Math.max(1, finishedInWindow.getAndSet(0) / seconds);
                long wait = (long) Math.ceil(waiting / finishedPerSecond);
                retryAfter = Duration.ofSeconds(Math.min(Math.max(wait, shortest.toSeconds()), longest.toSeconds()));
                windowStart = now;
            }
        }
        return retryAfter;
    }

    /** 측정 서버가 회차마다 상한을 켜고 끈다. 운영은 설정값만 쓴다. */
    public void setLimit(int limit) {
        this.limit = limit;
    }
}
