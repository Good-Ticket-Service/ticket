package com.sparta.ticket.performance.infrastructure.scheduling;

import com.sparta.ticket.performance.infrastructure.persistence.ScheduleJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 결과 확인 기회를 소진한 작업을 운영자가 발견할 수 있게 한다. ID는 라벨로 사용하지 않는다. */
@Component
public class SeatSetupRecoveryMetrics {
    public SeatSetupRecoveryMetrics(MeterRegistry registry, ScheduleJpaRepository repository) {
        Gauge.builder(
                        "performance.seat.setup.recovery.action.required",
                        repository,
                        ScheduleJpaRepository::countRecoveryActionRequired)
                .description("좌석 준비 결과 불명 상태로 자동 확인이 종료된 회차 수")
                .register(registry);
    }
}
