package com.sparta.ticket.reservation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.reservation.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.domain.ReservationTransition;
import com.sparta.ticket.reservation.support.FakeSeatPort;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import com.sparta.ticket.reservation.support.TestIds;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationTransitionExecutorIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private ReservationTransitionExecutor executor;

    @Autowired
    private FakeSeatPort seats;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final Long reservationId = TestIds.next();
    private final List<Long> seatIds = List.of(TestIds.next());

    @BeforeEach
    void setUp() {
        seats.reset();
    }

    private boolean execute(ReservationTransition transition, int updatedRows) {
        return transactionTemplate.execute(status -> executor.execute(transition, reservationId, seatIds, () -> {
            seats.events.add("update");
            return updatedRows;
        }));
    }

    @Test
    @DisplayName("좌석을 바꾸는 전이는 좌석 잠금, 조건부 UPDATE, 좌석 반영 순서로 실행한다")
    void locksSeatsBeforeUpdate() {
        assertThat(execute(ReservationTransition.CONFIRM, 1)).isTrue();
        assertThat(seats.events).containsExactly("lock", "update", "sell");

        seats.reset();
        assertThat(execute(ReservationTransition.EXPIRE_HOLD, 1)).isTrue();
        assertThat(seats.events).containsExactly("lock", "update", "release");
    }

    @Test
    @DisplayName("조건부 UPDATE가 0행이면 좌석을 반영하지 않는다")
    void doesNotTouchSeatsWhenNotUpdated() {
        assertThat(execute(ReservationTransition.CONFIRM, 0)).isFalse();

        assertThat(seats.events).containsExactly("lock", "update");
        assertThat(seats.sold).isEmpty();
    }

    @Test
    @DisplayName("좌석을 바꾸지 않는 전이는 좌석을 잠그지 않는다")
    void skipsSeatLockWithoutSeatEffect() {
        assertThat(execute(ReservationTransition.START_PAYMENT, 1)).isTrue();

        assertThat(seats.events).containsExactly("update");
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 막는다. 좌석과 예약은 한 트랜잭션에서 바뀌어야 한다")
    void requiresTransaction() {
        assertThatThrownBy(() -> executor.execute(ReservationTransition.CONFIRM, reservationId, seatIds, () -> 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(seats.events).isEmpty();
    }
}
