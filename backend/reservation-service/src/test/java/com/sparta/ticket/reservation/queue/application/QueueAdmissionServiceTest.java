package com.sparta.ticket.reservation.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.QueueState;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
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

    /** 열린 공연 목록을 정해 두고, 입장 처리를 부른 공연과 받은 규칙을 기록한다. failing에 든 공연은 Redis 장애처럼 실패한다. */
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
        public QueuePosition enter(long performanceId, long userId) {
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
            return new AdmissionResult(1, 0, 0, false);
        }
    }

    @Test
    @DisplayName("열린 공연마다 설정으로 만든 규칙으로 입장을 처리한다")
    void admitsEveryPerformance() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L)), Set.of());
        QueueAdmissionService service = new QueueAdmissionService(queue, new QueueProperties(true, 50), ADMISSION);

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
        QueueAdmissionService service = new QueueAdmissionService(queue, new QueueProperties(true, 50), ADMISSION);

        service.admitAll();

        assertThat(queue.admitted).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 아무 공연도 처리하지 않는다")
    void disabled() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(Set.of(7L), Set.of());
        QueueAdmissionService service = new QueueAdmissionService(queue, new QueueProperties(false, 50), ADMISSION);

        service.admitAll();

        assertThat(queue.admitted).isEmpty();
    }
}
