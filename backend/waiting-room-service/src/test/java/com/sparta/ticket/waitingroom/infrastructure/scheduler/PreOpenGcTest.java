package com.sparta.ticket.waitingroom.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.waitingroom.domain.WaitingRoom;
import com.sparta.ticket.waitingroom.domain.WaitingRoomRepository;
import com.sparta.ticket.waitingroom.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PreOpenGcTest {

    private static final LocalDateTime OPEN = LocalDateTime.of(2026, 11, 12, 20, 0, 0);

    private final MutableClock clock = new MutableClock(OPEN.minusMinutes(1));
    private final Rooms rooms = new Rooms();
    private final AtomicInteger gcCount = new AtomicInteger();
    private final PreOpenGc preOpenGc = new PreOpenGc(rooms, clock, Duration.ofSeconds(5), gcCount::incrementAndGet);

    @Test
    @DisplayName("오픈까지 lead보다 많이 남았으면 GC를 부르지 않는다")
    void beforeLead() {
        rooms.add(1, OPEN);
        clock.set(OPEN.minusSeconds(6));

        preOpenGc.check();

        assertThat(gcCount).hasValue(0);
    }

    @Test
    @DisplayName("오픈까지 lead 이하로 남으면 GC를 한 번 부르고, 같은 room으로는 다시 부르지 않는다")
    void onceWithinLead() {
        rooms.add(1, OPEN);

        clock.set(OPEN.minusSeconds(5));
        preOpenGc.check();
        clock.set(OPEN.minusSeconds(4));
        preOpenGc.check();
        clock.set(OPEN);
        preOpenGc.check();

        assertThat(gcCount).hasValue(1);
    }

    @Test
    @DisplayName("같은 때 열리는 room이 여럿이면 GC는 한 번이다")
    void onceForRoomsOpeningTogether() {
        rooms.add(1, OPEN);
        rooms.add(2, OPEN.plusSeconds(1));
        clock.set(OPEN.minusSeconds(4));

        preOpenGc.check();
        preOpenGc.check();

        assertThat(gcCount).hasValue(1);
    }

    @Test
    @DisplayName("이미 열린 room은 GC를 부르지 않는다")
    void skipOpenedRoom() {
        rooms.add(1, OPEN);
        clock.set(OPEN.plusSeconds(1));

        preOpenGc.check();

        assertThat(gcCount).hasValue(0);
    }

    @Test
    @DisplayName("room 목록을 읽지 못하면 GC를 부르지 않고 다음 확인에서 다시 본다")
    void retryAfterRepositoryFailure() {
        rooms.add(1, OPEN);
        clock.set(OPEN.minusSeconds(3));
        rooms.failing = true;

        preOpenGc.check();
        rooms.failing = false;
        preOpenGc.check();

        assertThat(gcCount).hasValue(1);
    }

    /** 끝나지 않은 room 목록만 돌려준다. 오픈 전 GC는 목록만 읽는다. */
    private static class Rooms implements WaitingRoomRepository {

        private final List<WaitingRoom> rooms = new ArrayList<>();
        private boolean failing;

        void add(long roomId, LocalDateTime openAt) {
            Instant open = openAt.atZone(java.time.ZoneId.systemDefault()).toInstant();
            rooms.add(new WaitingRoom(roomId, List.of("schedule:" + roomId), open, open.plusSeconds(3600)));
        }

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
            if (failing) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return List.copyOf(rooms);
        }
    }
}
