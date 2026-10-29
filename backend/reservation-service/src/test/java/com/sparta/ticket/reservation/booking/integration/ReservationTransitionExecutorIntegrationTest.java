package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestIds;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

class ReservationTransitionExecutorIntegrationTest extends BookingIntegrationTest {

    @Autowired
    private ReservationTransitionExecutor executor;

    private final Long reservationId = TestIds.next();
    private final List<Long> seatIds = List.of(TestIds.next());

    private boolean confirmAndSell(int updatedRows) {
        return transactionTemplate.execute(
                status -> executor.confirmAndSell(reservationId, seatIds, recordingUpdate(updatedRows)));
    }

    private boolean expireAndRelease(int updatedRows) {
        return transactionTemplate.execute(
                status -> executor.expireAndRelease(reservationId, seatIds, recordingUpdate(updatedRows)));
    }

    private IntSupplier recordingUpdate(int updatedRows) {
        return () -> {
            seats.events.add("update");
            return updatedRows;
        };
    }

    @Test
    @DisplayName("좌석을 바꾸는 전이는 좌석 잠금, 조건부 UPDATE, 좌석 반영 순서로 실행한다")
    void locksSeatsBeforeUpdate() {
        assertThat(confirmAndSell(1)).isTrue();
        assertThat(seats.events).containsExactly("lock", "update", "sell");

        seats.reset();
        assertThat(expireAndRelease(1)).isTrue();
        assertThat(seats.events).containsExactly("lock", "update", "release");
    }

    @Test
    @DisplayName("조건부 UPDATE가 0행이면 좌석을 반영하지 않는다")
    void doesNotTouchSeatsWhenNotUpdated() {
        assertThat(confirmAndSell(0)).isFalse();
        assertThat(seats.events).containsExactly("lock", "update");
        assertThat(seats.sold).isEmpty();

        seats.reset();
        assertThat(expireAndRelease(0)).isFalse();
        assertThat(seats.events).containsExactly("lock", "update");
    }

    @Test
    @DisplayName("판매 전이는 좌석이 이 예약의 선점이 아니면 예약도 좌석도 바꾸지 않는다")
    void doesNotSellSeatsHeldByOthers() {
        seats.notHeld(seatIds.get(0));

        assertThat(confirmAndSell(1)).isFalse();

        assertThat(seats.events).containsExactly("lock");
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 막는다. 좌석과 예약은 한 트랜잭션에서 바뀌어야 한다")
    void requiresTransaction() {
        assertThatThrownBy(() -> executor.confirmAndSell(reservationId, seatIds, () -> 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(seats.events).isEmpty();
    }
}
