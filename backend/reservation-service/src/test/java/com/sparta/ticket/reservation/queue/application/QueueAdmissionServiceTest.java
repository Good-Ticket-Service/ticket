package com.sparta.ticket.reservation.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.queue.domain.QueueState;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueAdmissionServiceTest {

    private static final QueueAdmissionProperties ADMISSION = new QueueAdmissionProperties(
            1000, Duration.ofSeconds(1), Duration.ofMinutes(2), Duration.ofMinutes(10), 1000);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private QueueAdmissionService service(RecordingWaitingQueue queue, boolean enabled) {
        return new QueueAdmissionService(
                queue, new QueueProperties(enabled, 50), ADMISSION, new QueueAdmissionMetrics(meters));
    }

    /** 열린 공연 목록을 정해 두고, 입장 처리를 부른 공연과 받은 규칙을 기록한다. failing에 든 공연은 Redis 장애처럼 실패한다.
     * 성공하면 공연마다 입장 3, 만료 1, 이탈 2, 남은 대기 5, 입장 중 10을 돌려준다. */
    private static final class RecordingWaitingQueue implements WaitingQueue {

        private final Set<Long> performances;
        private final Set<Long> failing;
        private final List<Long> admitted = new ArrayList<>();
        private AdmissionPolicy lastPolicy;

        RecordingWaitingQueue(Set<Long> performances, Set<Long> failing) {
            this.performances = performances;
            this.failing = failing;
        }

        @Override
        public QueueState enter(long performanceId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<QueueState> find(long performanceId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void leave(long performanceId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Long> performances() {
            return performances;
        }

        @Override
        public AdmissionResult admit(long performanceId, AdmissionPolicy policy) {
            admitted.add(performanceId);
            lastPolicy = policy;
            if (failing.contains(performanceId)) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return new AdmissionResult(3, 1, 2, false, 5, 10);
        }
    }

    @Test
    @DisplayName("열린 공연마다 설정으로 만든 규칙으로 입장을 처리한다")
    void admitsEveryPerformance() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L)), Set.of());
        QueueAdmissionService service = service(queue, true);

        service.admitAll();

        assertThat(queue.admitted).containsExactly(7L, 8L);
        assertThat(queue.lastPolicy)
                .isEqualTo(new AdmissionPolicy(
                        50, Duration.ofSeconds(1), 1000, Duration.ofMinutes(2), Duration.ofMinutes(10), 1000));
    }

    @Test
    @DisplayName("한 공연의 처리가 실패해도 다음 공연은 처리한다")
    void continuesAfterFailure() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L)), Set.of(7L));
        QueueAdmissionService service = service(queue, true);

        service.admitAll();

        assertThat(queue.admitted).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("입장·이탈·만료 인원을 누적하고, 대기·입장 중 인원은 그 주기에 처리한 공연들의 합으로 바꾼다")
    void recordsMetrics() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L, 9L)), Set.of(9L));
        QueueAdmissionService service = service(queue, true);

        service.admitAll();
        service.admitAll();

        assertThat(meters.counter("queue.admission.admitted").count()).isEqualTo(12);
        assertThat(meters.counter("queue.admission.left").count()).isEqualTo(8);
        assertThat(meters.counter("queue.admission.expired").count()).isEqualTo(4);
        assertThat(meters.get("queue.waiting").gauge().value()).isEqualTo(10);
        assertThat(meters.get("queue.active").gauge().value()).isEqualTo(20);
        // 실패한 공연도 처리 시간은 남긴다: 2주기 × 3공연
        assertThat(meters.timer("queue.admission.duration").count()).isEqualTo(6);
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 아무 공연도 처리하지 않는다")
    void disabled() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(Set.of(7L), Set.of());
        QueueAdmissionService service = service(queue, false);

        service.admitAll();

        assertThat(queue.admitted).isEmpty();
    }
}
