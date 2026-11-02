package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 입장 처리 지표. Prometheus가 /actuator/prometheus로 수집한다. 설정값(초당 입장, 상한, 이탈 기준 등)을 측정으로 다시 정하는 근거다.
 *
 * Note: 공연 ID 태그를 붙이지 않고 모든 공연의 합만 낸다. 공연마다 태그를 붙이면 공연 수만큼 시계열이 늘어나고,
 * 측정은 공연 한두 개로 하므로 합으로 충분하다.
 * 대기·입장 중 인원은 입장 처리 한 주기가 끝날 때 갱신하므로 스케줄 주기만큼 늦다. 처리에 실패한 공연은 그 주기의 합에서 빠진다.
 */
@Component
public class QueueAdmissionMetrics {

    private final Counter admitted;
    private final Counter left;
    private final Counter expired;
    private final Timer duration;
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong active = new AtomicLong();

    public QueueAdmissionMetrics(MeterRegistry meters) {
        this.admitted = Counter.builder("queue.admission.admitted")
                .description("입장시킨 인원")
                .register(meters);
        this.left = Counter.builder("queue.admission.left")
                .description("조회가 끊겨 줄에서 뺀 인원")
                .register(meters);
        this.expired = Counter.builder("queue.admission.expired")
                .description("입장 유효 시간이 지나 입장 목록에서 뺀 인원")
                .register(meters);
        this.duration = Timer.builder("queue.admission.duration")
                .description("공연 하나의 입장 처리(Redis 스크립트 한 번) 시간")
                .publishPercentileHistogram()
                .register(meters);
        Gauge.builder("queue.waiting", waiting, AtomicLong::get)
                .description("줄에 선 인원(모든 공연 합)")
                .register(meters);
        Gauge.builder("queue.active", active, AtomicLong::get)
                .description("입장해 있는 인원(모든 공연 합)")
                .register(meters);
    }

    /** 공연 하나의 입장 처리 시간을 잰다. */
    Timer.Sample start() {
        return Timer.start();
    }

    /** 공연 하나의 처리 결과를 누적한다. 실패한 처리도 시간은 남긴다. */
    void recorded(Timer.Sample sample, AdmissionResult result) {
        sample.stop(duration);
        admitted.increment(result.admitted());
        left.increment(result.left());
        expired.increment(result.expired());
    }

    void failed(Timer.Sample sample) {
        sample.stop(duration);
    }

    /** 한 주기가 끝나면 그 주기에 처리한 공연들의 대기·입장 중 인원 합으로 바꾼다. */
    void cycleFinished(long waitingTotal, long activeTotal) {
        waiting.set(waitingTotal);
        active.set(activeTotal);
    }
}
