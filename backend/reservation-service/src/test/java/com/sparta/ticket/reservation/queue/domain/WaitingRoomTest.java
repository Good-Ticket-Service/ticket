package com.sparta.ticket.reservation.queue.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WaitingRoomTest {

    private static final Instant OPEN_AT = Instant.parse("2026-11-07T11:00:00Z");
    private static final Instant CLOSE_AT = Instant.parse("2026-11-07T13:00:00Z");
    private final WaitingRoom room = new WaitingRoom(1L, List.of("schedule:12"), OPEN_AT, CLOSE_AT);

    @Test
    @DisplayName("openAt 전이면 409 WAITING_ROOM_NOT_OPEN")
    void beforeOpen() {
        assertThatThrownBy(() -> room.verifyEnterable(OPEN_AT.minusMillis(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.WAITING_ROOM_NOT_OPEN);
    }

    @Test
    @DisplayName("openAt 정각부터 closeAt 직전까지 줄을 받는다")
    void open() {
        assertThatCode(() -> room.verifyEnterable(OPEN_AT)).doesNotThrowAnyException();
        assertThatCode(() -> room.verifyEnterable(CLOSE_AT.minusMillis(1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("closeAt 정각부터 409 WAITING_ROOM_CLOSED")
    void closed() {
        assertThatThrownBy(() -> room.verifyEnterable(CLOSE_AT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.WAITING_ROOM_CLOSED);
        assertThatThrownBy(() -> room.verifyNotClosed(CLOSE_AT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.WAITING_ROOM_CLOSED);
    }

    @Test
    @DisplayName("오픈 전 순번 조회는 종료 검사만 한다")
    void notClosedBeforeOpen() {
        assertThatCode(() -> room.verifyNotClosed(OPEN_AT.minusSeconds(60))).doesNotThrowAnyException();
    }
}
