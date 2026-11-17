package com.sparta.ticket.reservation.seat.application.hold;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.ConvertOutcome;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.ConvertStart;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldState;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * 결제하기의 DB 트랜잭션이 업무 거절이 아닌 오류로 끝났을 때 Redis 선점을 어떻게 남기는지 확인한다(#449 동시성 리뷰 1번).
 * 트랜잭션을 시작하지 못했으면 예약이 없는 것이 확실해 HELD로 되돌리고, 커밋했는지 모르면 CONVERTING으로 둔다.
 */
class SeatHoldConversionFailureTest {

    private static final Long SCHEDULE = 1L;
    private static final Long USER = 1L;
    private static final Long SEAT = 11L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW =
            LocalDateTime.of(2026, 11, 1, 20, 0).atZone(SEOUL).toInstant();

    private SeatHoldStore store;
    private SeatHoldTransactions transactions;
    private SeatHoldConversionService service;
    private StoredHold hold;

    @BeforeEach
    void setUp() {
        store = mock(SeatHoldStore.class);
        transactions = mock(SeatHoldTransactions.class);
        ScheduleSeatRepository seats = mock(ScheduleSeatRepository.class);
        ScheduleSeat seat = ScheduleSeat.create(SCHEDULE, 501L, 7L, "VIP", 99_000, "A", "1", 1, true, 3L);
        when(seats.findById(SEAT)).thenReturn(Optional.of(seat));
        SaleValidation validation = new SaleValidation(
                true,
                null,
                SCHEDULE,
                100L,
                200L,
                3L,
                4L,
                LocalDateTime.of(2026, 12, 1, 19, 0),
                "공연",
                "MUSICAL",
                List.of(new SaleValidationSeat(501L, 7L, "VIP", 99_000)));
        hold = new StoredHold(
                "h_" + "a".repeat(32), SCHEDULE, USER, SEAT, validation, NOW.plusSeconds(600), HoldState.HELD, null);
        when(store.beginConvert(eq(SCHEDULE), eq(hold.holdId()), eq(USER), any(), any()))
                .thenReturn(new ConvertStart(ConvertOutcome.STARTED, null));
        when(store.find(SCHEDULE, hold.holdId())).thenReturn(Optional.of(hold));
        SeatHoldModeSwitch modeSwitch = mock(SeatHoldModeSwitch.class);
        when(modeSwitch.current()).thenReturn(SeatHoldMode.REDIS);
        when(modeSwitch.label()).thenReturn(SeatHoldMode.REDIS.name());
        service = new SeatHoldConversionService(
                store,
                seats,
                transactions,
                Clock.fixed(NOW, SEOUL),
                new RedisSeatHoldMetrics(new SimpleMeterRegistry()),
                mock(RedisHoldDbReads.class),
                new HoldStages(new SimpleMeterRegistry(), modeSwitch),
                modeSwitch);
    }

    @Test
    @DisplayName("DB 연결을 얻지 못하면 예약이 없는 것이 확실해 HELD로 되돌린다")
    void revertsWhenTransactionNeverStarted() {
        when(transactions.holdUntil(any(), any(), any(), any(), any()))
                .thenThrow(new CannotCreateTransactionException("연결 없음"));

        assertThatThrownBy(() -> service.convert(USER, SCHEDULE, hold.holdId()))
                .isInstanceOf(CannotCreateTransactionException.class);
        verify(store).abortConvert(hold, false);
    }

    @Test
    @DisplayName("커밋 중 오류처럼 결과를 모르면 되돌리지도 지우지도 않고 CONVERTING으로 둔다")
    void keepsConvertingWhenOutcomeUnknown() {
        when(transactions.holdUntil(any(), any(), any(), any(), any()))
                .thenThrow(new TransactionSystemException("커밋 응답 유실"));

        assertThatThrownBy(() -> service.convert(USER, SCHEDULE, hold.holdId()))
                .isInstanceOf(TransactionSystemException.class);
        verify(store, never()).abortConvert(any(), anyBoolean());
        verify(store, never()).finishConvert(any(), any(), any(), any());
    }
}
