package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.port.AdmissionSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 메시지 승인에서 결제 결과가 계속 늦어지면 결제 준비를 잠시 받지 않는다.
 *
 * <p>처리 능력은 PG 응답 시간과 DB 상태에 따라 열 배씩 바뀌어 받을 수 있는 수를 미리 정할 수 없다. 대신 접수한 결제가 정한 시간 안에
 * 결과를 받는지를 본다. 늦은 결제가 기준보다 많고 계속 늘면 받는 속도가 끝내는 속도를 넘은 것이라 거절하고, 다시 받는 기준 아래로
 * 내려오면 받는다. 늦은 결제가 많아도 늘지 않으면 느리지만 따라가고 있는 것이라 받는다. 막아도 받은 결제의 결과 시간은 줄지 않는다.
 *
 * <p>다시 받을 때는 한꺼번에 열지 않는다. 거절당한 사용자들이 Retry-After마다 다시 오며 기다리고 있어서, 활짝 열면 그 사람들이 몇 초 안에
 * 몰려 들어와 곧바로 다시 넘친다(측정: 다시 연 10초 동안 결제 중 102 → 2,759건). 그래서 먼저 결제가 끝나는 속도(직전 창의 결과 반영
 * 속도)만큼만 초마다 통과시키고, 늦은 결제가 늘지 않으면 단계마다 두 배로 늘린다. 한 단계 동안 막힌 요청이 없으면 들어오는 양이 그 수보다
 * 적다는 뜻이라 활짝 연다. 결과 반영 속도는 이 인스턴스가 반영한 수라, 인스턴스마다 자기 몫만큼 통과시켜 합이 전체 속도에 가깝다.
 *
 * <p>결제 중 집계가 1초마다 DB에서 센 값으로 갱신하므로 인스턴스가 여럿이어도 같은 값을 입력으로 받는다. 판단에 쓰는 이력과 거절 여부는
 * 인스턴스마다 쌓으므로 새로 뜬 인스턴스는 증가를 볼 이력이 찰 때까지(trend-window) 받는다. 만료 임박 통과 상한도 인스턴스마다 센다.
 * 요청은 마지막 판단만 읽는다.
 */
@Component
@RequiredArgsConstructor
public class AdmissionGate {

    private final AdmissionSettings settings;
    private final Clock clock;
    private final BookingMetrics metrics;

    private final Deque<Sample> samples = new ArrayDeque<>();
    private volatile Decision decision = Decision.NONE;
    private long prioritySecond = Long.MIN_VALUE;
    private int priorityUsed;
    private long rampSecond = Long.MIN_VALUE;
    private long rampUsed;
    private long heldBackInStep;
    private long rejectedSinceUpdate;
    private Duration retryAfter;

    private record Sample(LocalDateTime at, long late, long applied) {}

    private enum Mode {
        ACCEPTING,
        REOPENING,
        SHEDDING
    }

    /** reopenRate는 조금씩 여는 동안 초마다 통과시킬 수, stepStartedAt은 그 수를 정한 시각이다. */
    private record Decision(Mode mode, LocalDateTime at, long reopenRate, LocalDateTime stepStartedAt) {
        static final Decision NONE = new Decision(Mode.ACCEPTING, null, 0, null);

        Decision at(LocalDateTime time) {
            return new Decision(mode, time, reopenRate, stepStartedAt);
        }
    }

    /** 이 시각 전에 접수한 결제 중 예약이 늦은 결제다. */
    public LocalDateTime lateCutoff(LocalDateTime now) {
        return now.minus(settings.lateAfter());
    }

    /** 늦은 결제 수를 받아 거절 여부를 다시 정한다. */
    public synchronized void update(long late, LocalDateTime at) {
        // 시계가 뒤로 가면 이전 표본과 비교할 수 없어 처음부터 다시 쌓는다
        if (!samples.isEmpty() && !at.isAfter(samples.peekLast().at())) {
            samples.clear();
            decision = Decision.NONE;
            prioritySecond = Long.MIN_VALUE;
            priorityUsed = 0;
            rampSecond = Long.MIN_VALUE;
            rampUsed = 0;
            heldBackInStep = 0;
            rejectedSinceUpdate = 0;
            retryAfter = null;
        }
        LocalDateTime previous = samples.isEmpty() ? null : samples.peekLast().at();
        samples.addLast(new Sample(at, late, metrics.paymentResultsAppliedTotal()));
        dropOlderThan(at.minus(settings.trendWindow()));
        decision = next(decision, late, at);
        retryAfter = retryAfterFor(decision, previous, at);
        rejectedSinceUpdate = 0;
        metrics.admissionState(late, decision.mode() == Mode.SHEDDING, decision.reopenRate(), retryAfter.toSeconds());
    }

    /**
     * 거절당한 사용자는 Retry-After마다 다시 오므로, 지난 집계 뒤 초당 거절 수에 알려 준 Retry-After를 곱하면 기다리는 사용자 수다.
     * 그 수를 초마다 받을 수 있는 수로 나누면 마지막 사람의 차례가 올 때까지의 시간이고, 그만큼 기다리게 하면 다시 오는 요청이 받을 수
     * 있는 수에 가까워진다. 고정 간격이면 기다리는 사용자가 많을수록 거절만 되풀이하는 요청이 서버 스레드를 쓴다(측정: 5초 고정에서
     * 거절 초당 600~1,000건). 거절 중에는 다시 열 때 처음 통과시킬 결과 반영 속도로, 여는 중에는 그 단계의 초당 통과 수로 나눈다.
     */
    private Duration retryAfterFor(Decision current, LocalDateTime previous, LocalDateTime at) {
        long shortest = settings.retryAfter().toSeconds();
        if (current.mode() == Mode.ACCEPTING || previous == null) {
            return settings.retryAfter();
        }
        double seconds = Duration.between(previous, at).toMillis() / 1000.0;
        double waiting = rejectedSinceUpdate / seconds * currentRetryAfter().toSeconds();
        long admitPerSecond = current.mode() == Mode.SHEDDING ? Math.max(1, completionRate()) : current.reopenRate();
        long wait = (long) Math.ceil(waiting / admitPerSecond);
        return Duration.ofSeconds(
                Math.min(Math.max(wait, shortest), settings.maxRetryAfter().toSeconds()));
    }

    private Duration currentRetryAfter() {
        return retryAfter == null ? settings.retryAfter() : retryAfter;
    }

    private Decision next(Decision current, long late, LocalDateTime at) {
        return switch (current.mode()) {
            case ACCEPTING -> startsShedding(late, at) ? shedding(at) : current.at(at);
            case SHEDDING -> late <= settings.resumeBelow() ? reopening(completionRate(), at) : current.at(at);
            case REOPENING -> {
                if (late > settings.shedAbove()) {
                    yield shedding(at);
                }
                if (at.isBefore(
                        current.stepStartedAt().plus(settings.trendWindow().dividedBy(2)))) {
                    yield current.at(at);
                }
                if (heldBackInStep == 0) {
                    yield new Decision(Mode.ACCEPTING, at, 0, null);
                }
                // 늦은 결제가 다시 쌓이기 시작했으면 늘리지 않고 같은 수로 한 단계 더 본다
                yield reopening(late > settings.resumeBelow() ? current.reopenRate() : current.reopenRate() * 2, at);
            }
        };
    }

    private Decision shedding(LocalDateTime at) {
        return new Decision(Mode.SHEDDING, at, 0, null);
    }

    private Decision reopening(long rate, LocalDateTime at) {
        heldBackInStep = 0;
        return new Decision(Mode.REOPENING, at, Math.max(1, rate), at);
    }

    /** 직전 창 동안 이 인스턴스가 결제 결과를 반영한 초당 수. 거절하는 동안 밀린 결제를 비우는 속도라 결제 쪽 처리 능력에 가깝다. */
    private long completionRate() {
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        long seconds = Duration.between(first.at(), last.at()).toSeconds();
        return seconds <= 0 ? 0 : (last.applied() - first.applied()) / seconds;
    }

    /** 창의 시작점으로 쓸 표본 하나만 남기고 그보다 오래된 표본을 버린다. */
    private void dropOlderThan(LocalDateTime windowStart) {
        while (samples.size() > 1) {
            Sample first = samples.pollFirst();
            if (samples.peekFirst().at().isAfter(windowStart)) {
                samples.addFirst(first);
                return;
            }
        }
    }

    /**
     * 창의 앞 절반과 뒤 절반에서 모두 늘었는지 본다. 한 번 계단처럼 올라 그대로인 것(PG가 느려졌지만 따라감)은 한쪽 절반에서만 늘어
     * 거절하지 않는다. 절반마다 기준의 반 이상 늘어야 하는 것은 결제 수가 초마다 흔들리는 것을 늘어남으로 읽지 않기 위해서다.
     */
    private boolean startsShedding(long late, LocalDateTime now) {
        if (late <= settings.shedAbove()) {
            return false;
        }
        Sample start = sampleAtOrBefore(now.minus(settings.trendWindow()));
        Sample middle = sampleAtOrBefore(now.minus(settings.trendWindow().dividedBy(2)));
        if (start == null || middle == null) {
            return false;
        }
        long step = Math.max(1, settings.shedAbove() / 2);
        return middle.late() - start.late() >= step && late - middle.late() >= step;
    }

    private Sample sampleAtOrBefore(LocalDateTime at) {
        Sample found = null;
        for (Sample sample : samples) {
            if (sample.at().isAfter(at)) {
                break;
            }
            found = sample;
        }
        return found;
    }

    /**
     * 이 결제 준비를 받을지. 조금씩 여는 동안에는 이번 초의 몫을 하나 쓴다. 신호가 멈췄으면 막지 않는다. 결제 중 집계가 돌지 않는다고
     * 모든 결제를 막으면 집계 장애가 결제 장애가 되기 때문이다.
     */
    public synchronized boolean tryAdmit() {
        if (!settings.enabled()) {
            return true;
        }
        Decision current = decision;
        if (current.mode() == Mode.ACCEPTING) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(current.at()) || now.isAfter(current.at().plus(settings.staleAfter()))) {
            metrics.staleAdmissionSignal();
            return true;
        }
        if (current.mode() == Mode.SHEDDING) {
            return false;
        }
        long second = now.toEpochSecond(ZoneOffset.UTC);
        if (second != rampSecond) {
            rampSecond = second;
            rampUsed = 0;
        }
        if (rampUsed < current.reopenRate()) {
            rampUsed++;
            return true;
        }
        heldBackInStep++;
        return false;
    }

    /** 이번 초에 만료 임박 통과 몫이 남았는지. 몫이 없으면 선점 만료 시각을 읽지 않고 바로 거절한다. */
    public synchronized boolean hasPriorityRoom() {
        long second = LocalDateTime.now(clock).toEpochSecond(ZoneOffset.UTC);
        return second != prioritySecond || priorityUsed < settings.priorityRate();
    }

    /**
     * 거절 중에도 선점이 곧 끝나는 결제 준비는 초당 정한 수까지 통과시킨다. 우리 쪽 사정으로 오래 기다린 사용자가 좌석을 잃지 않게
     * 하되, 몰릴 때 문이 열리지 않게 수를 묶는다.
     */
    public synchronized boolean letsThroughBeforeHoldExpires(LocalDateTime holdExpiresAt) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (holdExpiresAt.isAfter(now.plus(settings.priorityWindow()))) {
            return false;
        }
        long second = now.toEpochSecond(ZoneOffset.UTC);
        if (second != prioritySecond) {
            prioritySecond = second;
            priorityUsed = 0;
        }
        if (priorityUsed >= settings.priorityRate()) {
            return false;
        }
        priorityUsed++;
        return true;
    }

    public synchronized AdmissionRejectedException reject() {
        rejectedSinceUpdate++;
        metrics.admissionRejected();
        return new AdmissionRejectedException(currentRetryAfter());
    }
}
