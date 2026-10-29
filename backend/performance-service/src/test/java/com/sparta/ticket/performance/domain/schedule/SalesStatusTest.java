package com.sparta.ticket.performance.domain.schedule;

import static com.sparta.ticket.performance.domain.performance.OperationStatus.ACTIVE;
import static com.sparta.ticket.performance.domain.performance.OperationStatus.CANCELED;
import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 판매 상태 계산 규칙. 명세는 .local/performance/api.md 「salesStatus 계산 규칙」이다. */
class SalesStatusTest {

    private static final LocalDateTime OPEN = LocalDateTime.of(2026, 11, 10, 14, 0);
    private static final LocalDateTime CLOSE = LocalDateTime.of(2026, 12, 1, 18, 0);
    private static final LocalDateTime DURING = LocalDateTime.of(2026, 11, 20, 12, 0);

    private static SalesStatus of(
            OperationStatus performance,
            OperationStatus schedule,
            boolean suspended,
            SeatSetupStatus setup,
            LocalDateTime now) {
        return SalesStatus.of(performance, schedule, OPEN, CLOSE, suspended, setup, now);
    }

    @Test
    @DisplayName("판매 기간 중이고 판매 중지가 아니며 좌석이 준비됐으면 ON_SALE")
    void onSale() {
        assertThat(of(ACTIVE, ACTIVE, false, SeatSetupStatus.READY, DURING)).isEqualTo(SalesStatus.ON_SALE);
    }

    @Test
    @DisplayName("공연이나 회차가 취소되면 시각·준비 상태와 관계없이 CANCELED")
    void canceledFirst() {
        assertThat(of(CANCELED, ACTIVE, false, SeatSetupStatus.READY, DURING)).isEqualTo(SalesStatus.CANCELED);
        assertThat(of(ACTIVE, CANCELED, false, SeatSetupStatus.READY, DURING)).isEqualTo(SalesStatus.CANCELED);
        assertThat(of(ACTIVE, CANCELED, true, SeatSetupStatus.FAILED, CLOSE)).isEqualTo(SalesStatus.CANCELED);
    }

    @Test
    @DisplayName("마감 시각 이후(같은 시각 포함)는 판매 중지·미준비여도 CLOSED")
    void closedBeforeUnavailable() {
        assertThat(of(ACTIVE, ACTIVE, false, SeatSetupStatus.READY, CLOSE)).isEqualTo(SalesStatus.CLOSED);
        assertThat(of(ACTIVE, ACTIVE, true, SeatSetupStatus.PENDING, CLOSE.plusDays(1)))
                .isEqualTo(SalesStatus.CLOSED);
    }

    @Test
    @DisplayName("오픈 시각 전은 판매 중지·미준비여도 BEFORE_OPEN, 오픈 시각과 같으면 열린 것으로 본다")
    void beforeOpen() {
        assertThat(of(ACTIVE, ACTIVE, true, SeatSetupStatus.PENDING, OPEN.minusSeconds(1)))
                .isEqualTo(SalesStatus.BEFORE_OPEN);
        assertThat(of(ACTIVE, ACTIVE, false, SeatSetupStatus.READY, OPEN)).isEqualTo(SalesStatus.ON_SALE);
    }

    @Test
    @DisplayName("판매 기간 중 판매 중지이거나 좌석 준비가 READY가 아니면 UNAVAILABLE")
    void unavailable() {
        assertThat(of(ACTIVE, ACTIVE, true, SeatSetupStatus.READY, DURING)).isEqualTo(SalesStatus.UNAVAILABLE);
        assertThat(of(ACTIVE, ACTIVE, false, SeatSetupStatus.PENDING, DURING)).isEqualTo(SalesStatus.UNAVAILABLE);
        assertThat(of(ACTIVE, ACTIVE, false, SeatSetupStatus.FAILED, DURING)).isEqualTo(SalesStatus.UNAVAILABLE);
    }
}
