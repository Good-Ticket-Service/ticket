package com.sparta.ticket.reservation.seat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 스냅샷 대기(get)·생성(load) 시간이 결과별로 기록되는지 Spring 없이 확인한다. */
class SeatSnapshotMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SeatSnapshotMetrics metrics = new SeatSnapshotMetrics(meters);
    private final SeatMapSnapshot snapshot =
            new SeatMapSnapshot(new SelectableSeatsResult(1L, 1L, LocalDateTime.of(2026, 11, 9, 12, 0), List.of()), "");

    private long count(String name, String... tags) {
        return meters.get(name).tags(tags).timer().count();
    }

    @Test
    @DisplayName("성공한 생성은 load(result=success)에, 요청 대기는 get에 한 번씩 남는다")
    void recordsSuccess() {
        SeatMapSnapshot result = metrics.timeGet(() -> metrics.timeLoad(() -> snapshot));

        assertThat(result).isSameAs(snapshot);
        assertThat(count("reservation.seat.snapshot.get")).isEqualTo(1);
        assertThat(count("reservation.seat.snapshot.load", "result", "success")).isEqualTo(1);
        assertThat(count("reservation.seat.snapshot.load", "result", "error")).isZero();
    }

    @Test
    @DisplayName("좌석이 없는 회차(404 SEAT_NOT_FOUND)는 error가 아니라 load(result=not_found)에 남는다")
    void recordsNotFoundSeparately() {
        assertThatThrownBy(() -> metrics.timeLoad(() -> {
                    throw new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND);
                }))
                .isInstanceOf(BusinessException.class);

        assertThat(count("reservation.seat.snapshot.load", "result", "not_found"))
                .isEqualTo(1);
        assertThat(count("reservation.seat.snapshot.load", "result", "error")).isZero();
    }

    @Test
    @DisplayName("생성이 실패하면 예외를 그대로 던지고 load(result=error)와 get에 남는다")
    void recordsError() {
        assertThatThrownBy(() -> metrics.timeGet(() -> metrics.timeLoad(() -> {
                    throw new IllegalStateException("좌석 없음");
                })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("reservation.seat.snapshot.get")).isEqualTo(1);
        assertThat(count("reservation.seat.snapshot.load", "result", "error")).isEqualTo(1);
        assertThat(count("reservation.seat.snapshot.load", "result", "success")).isZero();
    }
}
