package com.sparta.ticket.performance.domain.schedule;

import static com.sparta.ticket.performance.domain.performance.OperationStatus.ACTIVE;
import static com.sparta.ticket.performance.domain.performance.OperationStatus.CANCELED;
import static com.sparta.ticket.performance.domain.performance.PublicationStatus.DRAFT;
import static com.sparta.ticket.performance.domain.performance.PublicationStatus.PUBLISHED;
import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 판매 검증의 회차·공연 단위 거절 사유와 우선순위. */
class SaleConditionTest {

    private static final LocalDateTime OPEN = LocalDateTime.of(2026, 11, 10, 14, 0);
    private static final LocalDateTime CLOSE = LocalDateTime.of(2026, 12, 1, 18, 0);
    private static final LocalDateTime DURING = LocalDateTime.of(2026, 11, 20, 12, 0);
    private static final long CONFIG = 2L;

    private static SaleCondition condition(
            PublicationStatus publication,
            OperationStatus performance,
            OperationStatus schedule,
            boolean suspended,
            SeatSetupStatus setup) {
        return new SaleCondition(publication, performance, schedule, OPEN, CLOSE, suspended, setup, CONFIG);
    }

    private static SaleCondition sellable() {
        return condition(PUBLISHED, ACTIVE, ACTIVE, false, SeatSetupStatus.READY);
    }

    @Test
    @DisplayName("공개·정상 공연의 판매 기간 중 회차이고 구성 버전이 같으면 거절 사유가 없다")
    void allowed() {
        assertThat(sellable().rejectReason(DURING, CONFIG)).isEmpty();
    }

    @Test
    @DisplayName("공연 미공개가 가장 먼저다: 공연 취소·회차 취소·마감·미준비가 겹쳐도 PERFORMANCE_NOT_PUBLISHED")
    void notPublishedFirst() {
        SaleCondition all = condition(DRAFT, CANCELED, CANCELED, true, SeatSetupStatus.PENDING);
        assertThat(all.rejectReason(CLOSE, 1L)).contains(SaleRejectReason.PERFORMANCE_NOT_PUBLISHED);
    }

    @Test
    @DisplayName("공연 취소는 회차 취소보다, 회차 취소는 기간보다 먼저다")
    void cancellationBeforePeriod() {
        assertThat(condition(PUBLISHED, CANCELED, CANCELED, false, SeatSetupStatus.READY)
                        .rejectReason(CLOSE, CONFIG))
                .contains(SaleRejectReason.PERFORMANCE_CANCELED);
        assertThat(condition(PUBLISHED, ACTIVE, CANCELED, false, SeatSetupStatus.READY)
                        .rejectReason(CLOSE, CONFIG))
                .contains(SaleRejectReason.SCHEDULE_CANCELED);
    }

    @Test
    @DisplayName("기간(마감·오픈 전)은 판매 중지·좌석 미준비·구성 버전보다 먼저다. 마감 시각과 같으면 마감이다")
    void periodBeforeSuspensionAndSetup() {
        SaleCondition suspendedPending = condition(PUBLISHED, ACTIVE, ACTIVE, true, SeatSetupStatus.PENDING);
        assertThat(suspendedPending.rejectReason(CLOSE, 1L)).contains(SaleRejectReason.SALES_CLOSED);
        assertThat(suspendedPending.rejectReason(OPEN.minusSeconds(1), 1L)).contains(SaleRejectReason.BEFORE_OPEN);
    }

    @Test
    @DisplayName("오픈 시각과 같으면 열린 것으로 본다")
    void openAtBoundary() {
        assertThat(sellable().rejectReason(OPEN, CONFIG)).isEmpty();
    }

    @Test
    @DisplayName("판매 기간 중에는 판매 중지 → 좌석 미준비 → 구성 버전 불일치 순서다")
    void suspensionThenSetupThenVersion() {
        assertThat(condition(PUBLISHED, ACTIVE, ACTIVE, true, SeatSetupStatus.FAILED)
                        .rejectReason(DURING, 1L))
                .contains(SaleRejectReason.SALES_SUSPENDED);
        assertThat(condition(PUBLISHED, ACTIVE, ACTIVE, false, SeatSetupStatus.PENDING)
                        .rejectReason(DURING, 1L))
                .contains(SaleRejectReason.SEAT_SETUP_NOT_READY);
        assertThat(condition(PUBLISHED, ACTIVE, ACTIVE, false, SeatSetupStatus.FAILED)
                        .rejectReason(DURING, CONFIG))
                .contains(SaleRejectReason.SEAT_SETUP_NOT_READY);
        assertThat(sellable().rejectReason(DURING, 1L)).contains(SaleRejectReason.SEAT_CONFIG_VERSION_MISMATCH);
        assertThat(sellable().rejectReason(DURING, 3L)).contains(SaleRejectReason.SEAT_CONFIG_VERSION_MISMATCH);
    }
}
