package com.sparta.ticket.reservation.seat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldModeSwitch;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;
import com.sparta.ticket.reservation.seat.application.port.RemainingSeatCache;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatService;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeats;
import com.sparta.ticket.reservation.seat.domain.GradeRemaining;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REDIS 모드(#449)에서 Redis를 읽지 못해도 좌석 배치도·잔여석이 오류 없이 DB 기준으로 답하는지 확인한다. 둘 다 표시용이고,
 * 잡힌 좌석을 누르면 선점 API가 막는다.
 */
class RedisHoldDisplayFallbackTest {

    private static final SeatHoldModeSwitch REDIS_MODE =
            new SeatHoldModeSwitch(new SeatHoldProperties(Duration.ofMinutes(10), SeatHoldMode.REDIS));

    private static SeatHoldStore failingStore() {
        SeatHoldStore store = mock(SeatHoldStore.class);
        BusinessException down = new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        when(store.heldSeatIds(anyLong(), any())).thenThrow(down);
        when(store.heldCountByGrade(anyLong(), any(), any())).thenThrow(down);
        return store;
    }

    @Test
    @DisplayName("좌석 배치도는 DB 판정 그대로 만든다")
    void snapshotFallsBackToDb() {
        SelectableSeatQueryService queryService = mock(SelectableSeatQueryService.class);
        SelectableSeatResult seat = new SelectableSeatResult(11L, 501L, "A", "1", 1, 7L, "VIP", 150_000, true);
        when(queryService.getSelectableSeats(1L))
                .thenReturn(new SelectableSeatsResult(1L, 2L, LocalDateTime.of(2026, 11, 1, 20, 0), List.of(seat)));
        SeatMapSnapshotLoader loader = new SeatMapSnapshotLoader(
                queryService,
                new SeatSnapshotMetrics(new SimpleMeterRegistry()),
                REDIS_MODE,
                failingStore(),
                Clock.systemUTC());

        assertThat(loader.load(1L).result().seats()).containsExactly(seat);
    }

    @Test
    @DisplayName("잔여석은 DB 집계 그대로 준다")
    void remainingFallsBackToDb() {
        ScheduleSeatRepository repository = mock(ScheduleSeatRepository.class);
        List<GradeRemaining> grades = List.of(new GradeRemaining(7L, "VIP", 3));
        when(repository.countRemainingByGrade(1L)).thenReturn(grades);
        RemainingSeatCache cache = mock(RemainingSeatCache.class);
        when(cache.getOrLoad(any(), any()))
                .thenAnswer(invocation -> new RemainingSeatCache.Lookup(
                        invocation.<Supplier<RemainingSeats>>getArgument(1).get(), false));
        RemainingSeatService service =
                new RemainingSeatService(repository, cache, Clock.systemUTC(), REDIS_MODE, failingStore());

        assertThat(service.getRemaining(1L).grades()).isEqualTo(grades);
    }
}
