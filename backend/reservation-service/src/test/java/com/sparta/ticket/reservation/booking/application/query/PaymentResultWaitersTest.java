package com.sparta.ticket.reservation.booking.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentResultWaitersTest {

    private final BookingMetrics metrics = new BookingMetrics(new SimpleMeterRegistry());
    private final PaymentResultWaiters waiters = new PaymentResultWaiters(metrics);

    @Test
    @DisplayName("등록 뒤에 정해진 결과는 그 예약의 모든 대기에 한 번씩 알리고 목록에서 뺀다")
    void settlesEveryWaiterOnce() {
        List<ReservationStatus> answers = new ArrayList<>();
        waiters.register(1L, answers::add);
        waiters.register(1L, answers::add);

        waiters.settle(1L, ReservationStatus.CONFIRMED, System.currentTimeMillis());
        waiters.settle(1L, ReservationStatus.CONFIRMED, System.currentTimeMillis());

        assertThat(answers).containsExactly(ReservationStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        assertThat(metrics.resultWaitersCount()).isZero();
    }

    @Test
    @DisplayName("등록보다 먼저 정해진 결과로는 답하지 않고 대기를 남긴다")
    void keepsWaiterForSettlementBeforeRegistration() {
        List<ReservationStatus> answers = new ArrayList<>();
        waiters.register(1L, answers::add);

        waiters.settle(1L, ReservationStatus.HOLD, System.currentTimeMillis() - 60_000);

        assertThat(answers).isEmpty();
        assertThat(metrics.resultWaitersCount()).isEqualTo(1);
        waiters.settle(1L, ReservationStatus.CONFIRMED, System.currentTimeMillis());
        assertThat(answers).containsExactly(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("인스턴스 사이 시계 차이만큼 이른 결과는 받는다")
    void acceptsSettlementWithinClockTolerance() {
        List<ReservationStatus> answers = new ArrayList<>();
        waiters.register(1L, answers::add);

        waiters.settle(1L, ReservationStatus.CONFIRMED, System.currentTimeMillis() - 500);

        assertThat(answers).containsExactly(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("대기 하나의 응답 처리가 실패해도 다른 대기에는 답하고 대기 수가 맞는다")
    void failingWaiterDoesNotStopOthers() {
        List<ReservationStatus> answers = new ArrayList<>();
        waiters.register(1L, status -> {
            throw new IllegalStateException("dispatch");
        });
        waiters.register(1L, answers::add);

        waiters.settle(1L, ReservationStatus.CONFIRMED, System.currentTimeMillis());

        assertThat(answers).containsExactly(ReservationStatus.CONFIRMED);
        assertThat(metrics.resultWaitersCount()).isZero();
    }

    @Test
    @DisplayName("해제한 대기는 알림을 받지 않고, 두 번 해제해도 대기 수가 한 번만 준다")
    void unregisterRemovesOnce() {
        List<ReservationStatus> answers = new ArrayList<>();
        Runnable unregister = waiters.register(1L, answers::add);

        unregister.run();
        unregister.run();
        waiters.settle(1L, ReservationStatus.CONFIRMED, System.currentTimeMillis());

        assertThat(answers).isEmpty();
        assertThat(metrics.resultWaitersCount()).isZero();
    }
}
