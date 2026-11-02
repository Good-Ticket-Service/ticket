package com.sparta.ticket.reservation.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.QueueEntryResult;
import com.sparta.ticket.reservation.queue.application.dto.QueueStatusResult;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.queue.domain.QueueAdmission;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.QueueState;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

    private static final QueueAdmissionProperties ADMISSION = new QueueAdmissionProperties(
            1000, Duration.ofSeconds(2), Duration.ofMinutes(2), Duration.ofMinutes(10), 1000);

    /** 받은 값을 그대로 이어 붙여 토큰으로 돌려준다. 서비스가 어떤 값으로 발급을 부르는지 확인한다. */
    private static final EntryTokenIssuer ECHO_ISSUER = (userId, performanceId, admittedAt, expiresAt) ->
            userId + "|" + performanceId + "|" + admittedAt + "|" + expiresAt;

    /** 정해 둔 상태를 돌려주고 호출 횟수를 센다. state가 null이면 순번 조회에서 줄에 없는 회원이 된다. */
    private static final class FixedWaitingQueue implements WaitingQueue {

        private final QueueState state;
        private final AtomicInteger calls = new AtomicInteger();

        FixedWaitingQueue(QueueState state) {
            this.state = state;
        }

        @Override
        public QueuePosition enter(long performanceId, long userId) {
            calls.incrementAndGet();
            return (QueuePosition) state;
        }

        @Override
        public Optional<QueueState> find(long performanceId, long userId) {
            calls.incrementAndGet();
            return Optional.ofNullable(state);
        }

        @Override
        public void leave(long performanceId, long userId) {
            calls.incrementAndGet();
        }

        @Override
        public Set<Long> performances() {
            throw new UnsupportedOperationException("순번 서비스는 입장 처리를 부르지 않는다");
        }

        @Override
        public AdmissionResult admit(long performanceId, AdmissionPolicy policy) {
            throw new UnsupportedOperationException("순번 서비스는 입장 처리를 부르지 않는다");
        }
    }

    private static QueueService service(QueueState state, boolean enabled) {
        return service(new FixedWaitingQueue(state), enabled);
    }

    private static QueueService service(FixedWaitingQueue waitingQueue, boolean enabled) {
        return new QueueService(waitingQueue, new QueueProperties(enabled, 10), ADMISSION, ECHO_ISSUER);
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 줄을 세우지 않고 503 QUEUE_DISABLED")
    void disabled() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue, false);

        assertThatThrownBy(() -> service.enter(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.QUEUE_DISABLED);
        assertThat(waitingQueue.calls).hasValue(0);
    }

    @Test
    @DisplayName("예상 대기 시간은 앞 인원 ÷ 초당 입장 인원을 올림한 초다")
    void estimatedWaitSeconds() {
        assertThat(service(new QueuePosition(0), true).enter(1L, 7L)).isEqualTo(new QueueEntryResult(1, 0, 0));
        assertThat(service(new QueuePosition(10), true).enter(1L, 7L)).isEqualTo(new QueueEntryResult(11, 10, 1));
        assertThat(service(new QueuePosition(11), true).enter(1L, 7L)).isEqualTo(new QueueEntryResult(12, 11, 2));
    }

    @Test
    @DisplayName("순번 조회는 WAITING과 순번, 앞 인원, 예상 대기 시간을 돌려주고 토큰은 없다")
    void findMyStatus() {
        QueueStatusResult result = service(new QueuePosition(10), true).findMyStatus(1L, 7L);

        assertThat(result).isEqualTo(QueueStatusResult.waiting(11, 10, 1));
        assertThat(result.entryToken()).isNull();
    }

    @Test
    @DisplayName("입장해 있으면 ENTERED와 입장 토큰, +09:00 만료 시각을 돌려준다. 순번 필드는 0, 입장 시각은 만료 시각 − 입장 유효 시간이다")
    void findMyStatusEntered() {
        Instant expiresAt = Instant.parse("2026-11-02T11:10:00Z");

        QueueStatusResult result = service(new QueueAdmission(expiresAt), true).findMyStatus(1L, 7L);

        assertThat(result)
                .isEqualTo(QueueStatusResult.entered(
                        "7|1|2026-11-02T11:00:00Z|2026-11-02T11:10:00Z",
                        OffsetDateTime.of(2026, 11, 2, 20, 10, 0, 0, ZoneOffset.ofHours(9))));
        assertThat(result.position()).isZero();
    }

    @Test
    @DisplayName("줄에 없으면 순번 조회는 404 NOT_IN_QUEUE")
    void findMyStatusNotInQueue() {
        QueueService service = service((QueueState) null, true);

        assertThatThrownBy(() -> service.findMyStatus(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.NOT_IN_QUEUE);
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 순번을 조회하지 않고 503 QUEUE_DISABLED")
    void findMyStatusDisabled() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue, false);

        assertThatThrownBy(() -> service.findMyStatus(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.QUEUE_DISABLED);
        assertThat(waitingQueue.calls).hasValue(0);
    }

    @Test
    @DisplayName("대기 취소는 줄에서 빼는 일을 저장소에 넘긴다")
    void leave() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue, true);

        service.leave(1L, 7L);

        assertThat(waitingQueue.calls).hasValue(1);
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 대기 취소도 503 QUEUE_DISABLED")
    void leaveDisabled() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue, false);

        assertThatThrownBy(() -> service.leave(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.QUEUE_DISABLED);
        assertThat(waitingQueue.calls).hasValue(0);
    }
}
