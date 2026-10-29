package com.sparta.ticket.reservation.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 상태는 조건부 UPDATE로만 바뀌므로, 다시 읽은 예약을 흉내 내려고 상태와 orderId를 직접 넣는다. */
class ReservationTest {

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 11, 1, 20, 10);
    private static final String ORDER_ID = "order-1";

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    @DisplayName("isCurrentPayment: 같은 orderId의 결제 중일 때만 참이다")
    void isCurrentPayment(ReservationStatus status) {
        Reservation reservation = reservation(status, ORDER_ID);

        assertThat(reservation.isCurrentPayment(ORDER_ID)).isEqualTo(status == ReservationStatus.PAYMENT_IN_PROGRESS);
        assertThat(reservation.isCurrentPayment("order-2")).isFalse();
        assertThat(reservation.isCurrentPayment(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    @DisplayName("isConfirmedBy: 같은 orderId로 확정됐을 때만 참이다")
    void isConfirmedBy(ReservationStatus status) {
        Reservation reservation = reservation(status, ORDER_ID);

        assertThat(reservation.isConfirmedBy(ORDER_ID)).isEqualTo(status == ReservationStatus.CONFIRMED);
        assertThat(reservation.isConfirmedBy("order-2")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    @DisplayName("isClosedHold: 해제나 만료로 끝난 선점일 때만 참이다")
    void isClosedHold(ReservationStatus status) {
        assertThat(reservation(status, null).isClosedHold())
                .isEqualTo(status == ReservationStatus.RELEASED || status == ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("isExpirableAt: 선점 중이고 만료 시각이 지났거나 같으면 참이다")
    void isExpirableAtForHold() {
        Reservation reservation = reservation(ReservationStatus.HOLD, null);

        assertThat(reservation.isExpirableAt(EXPIRES_AT.minusSeconds(1))).isFalse();
        assertThat(reservation.isExpirableAt(EXPIRES_AT)).isTrue();
        assertThat(reservation.isExpirableAt(EXPIRES_AT.plusSeconds(1))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"HOLD"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("isExpirableAt: 선점이 아니면 시각이 지나도 거짓이다")
    void isExpirableAtOnlyForHold(ReservationStatus status) {
        assertThat(reservation(status, null).isExpirableAt(EXPIRES_AT.plusMinutes(1)))
                .isFalse();
    }

    private static Reservation reservation(ReservationStatus status, String orderId) {
        Reservation reservation = Reservation.hold(
                1L,
                new SaleTarget(10L, 20L, 30L, LocalDateTime.of(2026, 12, 1, 19, 0), "공연"),
                List.of(new SeatPrice(100L, 10_000)),
                EXPIRES_AT);
        ReflectionTestUtils.setField(reservation, "status", status);
        ReflectionTestUtils.setField(reservation, "orderId", orderId);
        return reservation;
    }
}
