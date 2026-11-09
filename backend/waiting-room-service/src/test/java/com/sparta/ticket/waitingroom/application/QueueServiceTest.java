package com.sparta.ticket.waitingroom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.waitingroom.application.dto.QueueStatusResult;
import com.sparta.ticket.waitingroom.domain.AdmissionPolicy;
import com.sparta.ticket.waitingroom.domain.AdmissionResult;
import com.sparta.ticket.waitingroom.domain.QueueAdmission;
import com.sparta.ticket.waitingroom.domain.QueueErrorCode;
import com.sparta.ticket.waitingroom.domain.QueuePosition;
import com.sparta.ticket.waitingroom.domain.QueueState;
import com.sparta.ticket.waitingroom.domain.WaitingQueue;
import com.sparta.ticket.waitingroom.domain.WaitingRoom;
import com.sparta.ticket.waitingroom.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

    private static final QueueAdmissionProperties ADMISSION = new QueueAdmissionProperties(
            1000, Duration.ofSeconds(2), Duration.ofMinutes(2), Duration.ofMinutes(10), 1000);

    /** 받은 값을 그대로 이어 붙여 토큰으로 돌려준다. 서비스가 어떤 값으로 발급을 부르는지 확인한다. */
    private static final EntryTokenIssuer ECHO_ISSUER = (userId, roomId, scopes, admittedAt, expiresAt) ->
            userId + "|" + roomId + "|" + scopes + "|" + admittedAt + "|" + expiresAt;

    private static final Instant NOW = Instant.parse("2026-11-02T11:00:00Z");

    /** room 1은 NOW에 열려 있다. 다른 ID는 없는 room이다. */
    private static final WaitingRoom OPEN_ROOM =
            new WaitingRoom(1L, List.of("schedule:12"), NOW.minusSeconds(60), NOW.plusSeconds(3600));

    /** 정해 둔 room 하나만 ID로 찾는다. 시각 검사만 보므로 생성·목록은 부르지 않는다. */
    private record FixedRoomRepository(WaitingRoom room) implements WaitingRoomRepository {

        @Override
        public WaitingRoom create(List<String> scopes, Instant openAt, Instant closeAt, Instant keepUntil) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WaitingRoom> findById(long roomId) {
            return Optional.of(room).filter(r -> r.roomId() == roomId);
        }

        @Override
        public Optional<WaitingRoom> findByScope(String scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WaitingRoom> findActive() {
            throw new UnsupportedOperationException();
        }
    }

    /** 정해 둔 상태를 돌려주고 호출 횟수를 센다. state가 null이면 순번 조회에서 줄에 없는 회원이 된다. */
    private static final class FixedWaitingQueue implements WaitingQueue {

        private final QueueState state;
        private final AtomicInteger calls = new AtomicInteger();

        FixedWaitingQueue(QueueState state) {
            this.state = state;
        }

        @Override
        public QueueState enter(long roomId, long userId) {
            calls.incrementAndGet();
            return state;
        }

        @Override
        public Optional<QueueState> find(long roomId, long userId) {
            calls.incrementAndGet();
            return Optional.ofNullable(state);
        }

        @Override
        public void leave(long roomId, long userId) {
            calls.incrementAndGet();
        }

        @Override
        public AdmissionResult admit(long roomId, AdmissionPolicy policy) {
            throw new UnsupportedOperationException("순번 서비스는 입장 처리를 부르지 않는다");
        }
    }

    private static QueueService service(QueueState state) {
        return service(new FixedWaitingQueue(state));
    }

    private static QueueService service(FixedWaitingQueue waitingQueue) {
        return service(waitingQueue, OPEN_ROOM);
    }

    private static QueueService service(FixedWaitingQueue waitingQueue, WaitingRoom room) {
        return new QueueService(
                waitingQueue,
                new QueueProperties(10),
                ADMISSION,
                ECHO_ISSUER,
                new FixedRoomRepository(room),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertError(Runnable call, QueueErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("room이 없으면 진입·순번 조회 모두 줄을 보지 않고 404 WAITING_ROOM_NOT_FOUND")
    void roomNotFound() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue);

        assertError(() -> service.enter(2L, 7L), QueueErrorCode.WAITING_ROOM_NOT_FOUND);
        assertError(() -> service.findMyStatus(2L, 7L), QueueErrorCode.WAITING_ROOM_NOT_FOUND);
        assertThat(waitingQueue.calls).hasValue(0);
    }

    @Test
    @DisplayName("오픈 전이면 진입은 409 WAITING_ROOM_NOT_OPEN이고, 순번 조회는 줄을 본다(줄에 없으니 404 NOT_IN_QUEUE)")
    void roomNotOpen() {
        WaitingRoom ready = new WaitingRoom(1L, List.of("schedule:12"), NOW.plusSeconds(1), NOW.plusSeconds(3600));
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(null);
        QueueService service = service(waitingQueue, ready);

        assertError(() -> service.enter(1L, 7L), QueueErrorCode.WAITING_ROOM_NOT_OPEN);
        assertError(() -> service.findMyStatus(1L, 7L), QueueErrorCode.NOT_IN_QUEUE);
        assertThat(waitingQueue.calls).hasValue(1);
    }

    @Test
    @DisplayName("closeAt이 되면 진입·순번 조회 모두 줄을 보지 않고 409 WAITING_ROOM_CLOSED. 대기 취소는 그대로 성공")
    void roomClosed() {
        WaitingRoom closed = new WaitingRoom(1L, List.of("schedule:12"), NOW.minusSeconds(3600), NOW);
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue, closed);

        assertError(() -> service.enter(1L, 7L), QueueErrorCode.WAITING_ROOM_CLOSED);
        assertError(() -> service.findMyStatus(1L, 7L), QueueErrorCode.WAITING_ROOM_CLOSED);
        assertThat(waitingQueue.calls).hasValue(0);

        service.leave(1L, 7L);
        assertThat(waitingQueue.calls).hasValue(1);
    }

    @Test
    @DisplayName("예상 대기 시간은 앞 인원 ÷ 초당 입장 인원을 올림한 초다")
    void estimatedWaitSeconds() {
        assertThat(service(new QueuePosition(0)).enter(1L, 7L)).isEqualTo(QueueStatusResult.waiting(1, 0, 0));
        assertThat(service(new QueuePosition(10)).enter(1L, 7L)).isEqualTo(QueueStatusResult.waiting(11, 10, 1));
        assertThat(service(new QueuePosition(11)).enter(1L, 7L)).isEqualTo(QueueStatusResult.waiting(12, 11, 2));
    }

    @Test
    @DisplayName("순번 조회는 WAITING과 순번, 앞 인원, 예상 대기 시간을 돌려주고 토큰은 없다")
    void findMyStatus() {
        QueueStatusResult result = service(new QueuePosition(10)).findMyStatus(1L, 7L);

        assertThat(result).isEqualTo(QueueStatusResult.waiting(11, 10, 1));
        assertThat(result.entryToken()).isNull();
    }

    @Test
    @DisplayName("입장해 있으면 ENTERED와 입장 토큰, +09:00 만료 시각을 돌려준다. 순번 필드는 0, 입장 시각은 만료 시각 − 입장 유효 시간이다")
    void findMyStatusEntered() {
        Instant expiresAt = Instant.parse("2026-11-02T11:10:00Z");

        QueueStatusResult result = service(new QueueAdmission(expiresAt)).findMyStatus(1L, 7L);

        assertThat(result)
                .isEqualTo(QueueStatusResult.entered(
                        "7|1|[schedule:12]|2026-11-02T11:00:00Z|2026-11-02T11:10:00Z",
                        OffsetDateTime.of(2026, 11, 2, 20, 10, 0, 0, ZoneOffset.ofHours(9))));
        assertThat(result.position()).isZero();
    }

    @Test
    @DisplayName("입장해 있는 회원이 다시 진입하면 순번 조회와 같은 ENTERED 응답을 준다")
    void enterWhileEntered() {
        QueueService service = service(new QueueAdmission(Instant.parse("2026-11-02T11:10:00Z")));

        assertThat(service.enter(1L, 7L)).isEqualTo(service.findMyStatus(1L, 7L));
    }

    @Test
    @DisplayName("줄에 없으면 순번 조회는 404 NOT_IN_QUEUE")
    void findMyStatusNotInQueue() {
        QueueService service = service((QueueState) null);

        assertThatThrownBy(() -> service.findMyStatus(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.NOT_IN_QUEUE);
    }

    @Test
    @DisplayName("대기 취소는 줄에서 빼는 일을 저장소에 넘긴다")
    void leave() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(new QueuePosition(0));
        QueueService service = service(waitingQueue);

        service.leave(1L, 7L);

        assertThat(waitingQueue.calls).hasValue(1);
    }
}
