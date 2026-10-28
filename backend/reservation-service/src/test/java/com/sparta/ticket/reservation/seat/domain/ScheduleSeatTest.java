package com.sparta.ticket.reservation.seat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 허용한 전이만 일어나고, 그 밖의 전이는 막는다. */
class ScheduleSeatTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long RESERVATION = 10L;
    private static final Long OTHER_RESERVATION = 20L;

    private static ScheduleSeat available() {
        return ScheduleSeat.create(1L, 100L, 7L, "VIP", 150_000, "A", "1", 1, true, 1L);
    }

    private static ScheduleSeat heldUntil(LocalDateTime holdExpiresAt) {
        ScheduleSeat seat = available();
        seat.hold(RESERVATION, holdExpiresAt, NOW);
        return seat;
    }

    private static ScheduleSeat sold() {
        ScheduleSeat seat = heldUntil(NOW.plusMinutes(10));
        seat.sell(RESERVATION);
        return seat;
    }

    private static void assertRejected(Runnable transition, CommonErrorCode code) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(code);
    }

    private static void assertRejected(Runnable transition, ReservationErrorCode code) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(code);
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("AVAILABLE로 만들고 점유 예약과 만료 시각이 없다")
        void createsAvailable() {
            ScheduleSeat seat = available();

            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.getReservationId()).isNull();
            assertThat(seat.getHoldExpiresAt()).isNull();
        }

        @Test
        @DisplayName("판매 제외 좌석은 가격 없이 만들 수 있다")
        void unsellableWithoutPrice() {
            ScheduleSeat seat = ScheduleSeat.create(1L, 100L, 7L, "VIP", null, "A", "1", 1, false, 1L);

            assertThat(seat.getBasePrice()).isNull();
            assertThat(seat.isSellable()).isFalse();
        }

        @Test
        @DisplayName("판매 대상인데 가격이 없거나 0원 이하면 만들 수 없다")
        void sellableNeedsPositivePrice() {
            assertRejected(
                    () -> ScheduleSeat.create(1L, 100L, 7L, "VIP", null, "A", "1", 1, true, 1L),
                    CommonErrorCode.INVALID_REQUEST);
            assertRejected(
                    () -> ScheduleSeat.create(1L, 100L, 7L, "VIP", 0, "A", "1", 1, true, 1L),
                    CommonErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("좌석 번호와 구성 버전은 1 이상이어야 한다")
        void seatNumberAndConfigVersion() {
            assertRejected(
                    () -> ScheduleSeat.create(1L, 100L, 7L, "VIP", 150_000, "A", "1", 0, true, 1L),
                    CommonErrorCode.INVALID_REQUEST);
            assertRejected(
                    () -> ScheduleSeat.create(1L, 100L, 7L, "VIP", 150_000, "A", "1", 1, true, 0L),
                    CommonErrorCode.INVALID_REQUEST);
        }
    }

    @Nested
    @DisplayName("선점")
    class Hold {

        @Test
        @DisplayName("AVAILABLE을 HOLD로 바꾸고 예약 id와 만료 시각을 넣는다")
        void holdsAvailable() {
            ScheduleSeat seat = available();

            seat.hold(RESERVATION, NOW.plusMinutes(10), NOW);

            assertThat(seat.getStatus()).isEqualTo(SeatStatus.HOLD);
            assertThat(seat.getReservationId()).isEqualTo(RESERVATION);
            assertThat(seat.getHoldExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        }

        @Test
        @DisplayName("만료 시각 정각이 지난 HOLD는 새 예약이 다시 선점한다")
        void holdsExpiredHold() {
            ScheduleSeat seat = heldUntil(NOW);

            seat.hold(OTHER_RESERVATION, NOW.plusMinutes(10), NOW);

            assertThat(seat.getStatus()).isEqualTo(SeatStatus.HOLD);
            assertThat(seat.getReservationId()).isEqualTo(OTHER_RESERVATION);
            assertThat(seat.getHoldExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        }

        @Test
        @DisplayName("만료 1초 전의 HOLD는 선점할 수 없다")
        void rejectsLiveHold() {
            ScheduleSeat seat = heldUntil(NOW.plusSeconds(1));

            assertRejected(
                    () -> seat.hold(OTHER_RESERVATION, NOW.plusMinutes(10), NOW),
                    ReservationErrorCode.SEAT_ALREADY_TAKEN);
            assertThat(seat.getReservationId()).isEqualTo(RESERVATION);
        }

        @Test
        @DisplayName("SOLD는 HOLD로 바꿀 수 없다")
        void rejectsSold() {
            ScheduleSeat seat = sold();

            assertRejected(
                    () -> seat.hold(OTHER_RESERVATION, NOW.plusMinutes(10), NOW),
                    ReservationErrorCode.SEAT_ALREADY_TAKEN);
            assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
        }

        @Test
        @DisplayName("판매 제외 좌석은 AVAILABLE이어도 선점할 수 없다")
        void rejectsUnsellable() {
            ScheduleSeat seat = ScheduleSeat.create(1L, 100L, 7L, "VIP", null, "A", "1", 1, false, 1L);

            assertRejected(
                    () -> seat.hold(RESERVATION, NOW.plusMinutes(10), NOW), ReservationErrorCode.SEAT_NOT_SELLABLE);
            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("판매")
    class Sell {

        @Test
        @DisplayName("이 예약의 HOLD를 SOLD로 바꾸고 만료 시각을 비운다. 예약 id는 남는다")
        void sellsOwnHold() {
            ScheduleSeat seat = sold();

            assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
            assertThat(seat.getReservationId()).isEqualTo(RESERVATION);
            assertThat(seat.getHoldExpiresAt()).isNull();
        }

        @Test
        @DisplayName("다른 예약의 HOLD, AVAILABLE, SOLD는 판매할 수 없다")
        void rejectsOtherwise() {
            ScheduleSeat othersHold = heldUntil(NOW.plusMinutes(10));
            assertRejected(() -> othersHold.sell(OTHER_RESERVATION), CommonErrorCode.INVALID_STATUS_TRANSITION);

            assertRejected(() -> available().sell(RESERVATION), CommonErrorCode.INVALID_STATUS_TRANSITION);
            assertRejected(() -> sold().sell(RESERVATION), CommonErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    @Nested
    @DisplayName("반환")
    class Release {

        @Test
        @DisplayName("이 예약의 HOLD를 AVAILABLE로 돌리고 예약 id와 만료 시각을 비운다")
        void releasesOwnHold() {
            ScheduleSeat seat = heldUntil(NOW.plusMinutes(10));

            seat.release(RESERVATION);

            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.getReservationId()).isNull();
            assertThat(seat.getHoldExpiresAt()).isNull();
        }

        @Test
        @DisplayName("이 예약의 SOLD도 AVAILABLE로 돌린다(취소)")
        void releasesOwnSold() {
            ScheduleSeat seat = sold();

            seat.release(RESERVATION);

            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.getReservationId()).isNull();
        }

        @Test
        @DisplayName("다른 예약에 넘어간 좌석과 AVAILABLE은 반환할 수 없다")
        void rejectsOtherwise() {
            ScheduleSeat othersHold = heldUntil(NOW.plusMinutes(10));
            assertRejected(() -> othersHold.release(OTHER_RESERVATION), CommonErrorCode.INVALID_STATUS_TRANSITION);
            assertThat(othersHold.getReservationId()).isEqualTo(RESERVATION);

            assertRejected(() -> available().release(RESERVATION), CommonErrorCode.INVALID_STATUS_TRANSITION);
        }
    }
}
