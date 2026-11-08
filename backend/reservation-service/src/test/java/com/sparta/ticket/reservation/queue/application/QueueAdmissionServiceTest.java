package com.sparta.ticket.reservation.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.queue.domain.QueueState;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
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

    private QueueAdmissionService service(RecordingWaitingQueue queue) {
        return new QueueAdmissionService(
                queue,
                new ActiveRooms(queue.roomIds),
                new QueueProperties(50),
                ADMISSION,
                new QueueAdmissionMetrics(meters));
    }

    /** 정해 둔 roomId들을 끝나지 않은 room 목록으로 돌려준다. 스케줄러는 목록만 읽는다. */
    private record ActiveRooms(Set<Long> roomIds) implements WaitingRoomRepository {

        @Override
        public WaitingRoom create(List<String> scopes, Instant openAt, Instant closeAt, Instant keepUntil) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WaitingRoom> findById(long roomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WaitingRoom> findByScope(String scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WaitingRoom> findActive() {
            Instant now = Instant.parse("2026-11-02T11:00:00Z");
            return roomIds.stream()
                    .map(id -> new WaitingRoom(id, List.of("schedule:" + id), now, now.plusSeconds(3600)))
                    .toList();
        }
    }

    /** 입장 처리를 부른 room과 받은 규칙을 기록한다. failing에 든 room은 Redis 장애처럼 실패한다.
     * 성공하면 room마다 입장 3, 만료 1, 이탈 2, 남은 대기 5, 입장 중 10을 돌려준다. */
    private static final class RecordingWaitingQueue implements WaitingQueue {

        private final Set<Long> roomIds;
        private final Set<Long> failing;
        private final List<Long> admitted = new ArrayList<>();
        private AdmissionPolicy lastPolicy;

        RecordingWaitingQueue(Set<Long> roomIds, Set<Long> failing) {
            this.roomIds = roomIds;
            this.failing = failing;
        }

        @Override
        public QueueState enter(long roomId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<QueueState> find(long roomId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void leave(long roomId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AdmissionResult admit(long roomId, AdmissionPolicy policy) {
            admitted.add(roomId);
            lastPolicy = policy;
            if (failing.contains(roomId)) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return new AdmissionResult(3, 1, 2, 5, 10);
        }
    }

    @Test
    @DisplayName("끝나지 않은 room마다 설정으로 만든 규칙으로 입장을 처리한다")
    void admitsEveryRoom() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L)), Set.of());
        QueueAdmissionService service = service(queue);

        service.admitAll();

        assertThat(queue.admitted).containsExactly(7L, 8L);
        assertThat(queue.lastPolicy)
                .isEqualTo(new AdmissionPolicy(
                        50, Duration.ofSeconds(1), 1000, Duration.ofMinutes(2), Duration.ofMinutes(10), 1000));
    }

    @Test
    @DisplayName("한 room의 처리가 실패해도 다음 room은 처리한다")
    void continuesAfterFailure() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L)), Set.of(7L));
        QueueAdmissionService service = service(queue);

        service.admitAll();

        assertThat(queue.admitted).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("입장·이탈·만료 인원을 누적하고, 대기·입장 중 인원은 그 주기에 처리한 room들의 합으로 바꾼다")
    void recordsMetrics() {
        RecordingWaitingQueue queue = new RecordingWaitingQueue(new LinkedHashSet<>(List.of(7L, 8L, 9L)), Set.of(9L));
        QueueAdmissionService service = service(queue);

        service.admitAll();
        service.admitAll();

        assertThat(meters.counter("queue.admission.admitted").count()).isEqualTo(12);
        assertThat(meters.counter("queue.admission.left").count()).isEqualTo(8);
        assertThat(meters.counter("queue.admission.expired").count()).isEqualTo(4);
        assertThat(meters.get("queue.waiting").gauge().value()).isEqualTo(10);
        assertThat(meters.get("queue.active").gauge().value()).isEqualTo(20);
        // 실패한 room도 처리 시간은 남긴다: 2주기 × 3 room
        assertThat(meters.timer("queue.admission.duration").count()).isEqualTo(6);
    }
}
