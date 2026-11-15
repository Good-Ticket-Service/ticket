package com.sparta.ticket.reservation.seat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.application.SeatMapSnapshotLoader;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldConversionService;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldService;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatService;
import com.sparta.ticket.reservation.seat.domain.GradeRemaining;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.support.FakeSaleValidationPort;
import com.sparta.ticket.reservation.seat.support.MutableSeatHoldModeSwitch;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * REDIS 모드(#449)에서 Redis로 잡힌 좌석이 좌석 배치도에서는 선택 불가, 잔여석에서는 빠지는지 확인한다.
 * Redis로 잡힌 좌석은 DB에서 AVAILABLE이라, 덮지 않으면 빈자리로 보이고 잔여석이 많게 세어진다.
 */
class RedisSeatHoldDisplayIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 705L;
    private static final Long GRADE = 7L;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatHoldConversionService conversionService;

    @Autowired
    private SeatMapSnapshotLoader snapshotLoader;

    @Autowired
    private RemainingSeatService remainingSeatService;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private FakeSaleValidationPort saleValidation;

    @Autowired
    private MutableSeatHoldModeSwitch modeSwitch;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MutableTicker ticker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        Set<String> keys = redis.keys("seathold:{s" + SCHEDULE + "}:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        saleValidation.reset();
        clock.set(NOW);
        modeSwitch.set(SeatHoldMode.REDIS);
    }

    @AfterEach
    void tearDown() {
        modeSwitch.reset();
    }

    private Long seat() {
        long venueSeatId = nextVenueSeatId++;
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE,
                        venueSeatId,
                        GRADE,
                        "VIP",
                        FakeSaleValidationPort.DEFAULT_PRICE,
                        "A",
                        "1",
                        (int) venueSeatId,
                        true,
                        2L))
                .getId();
    }

    private Map<Long, Boolean> selectable() {
        return snapshotLoader.load(SCHEDULE).result().seats().stream()
                .collect(Collectors.toMap(SelectableSeatResult::scheduleSeatId, SelectableSeatResult::selectable));
    }

    /** 잔여석 캐시(3초)를 넘겨 새로 센 값. */
    private long remaining() {
        ticker.advance(Duration.ofMinutes(1));
        return remainingSeatService.getRemaining(SCHEDULE).grades().stream()
                .filter(grade -> grade.venueSeatGradeId().equals(GRADE))
                .mapToLong(GradeRemaining::remaining)
                .sum();
    }

    @Test
    @DisplayName("Redis로 잡힌 좌석은 배치도에서 선택 불가, 잔여석에서 빠진다")
    void heldSeatHidden() {
        Long held = seat();
        Long free = seat();
        seatHoldService.hold(1L, SCHEDULE, held);

        assertThat(selectable()).containsEntry(held, false).containsEntry(free, true);
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 Redis 선점은 다시 선택 가능하고 잔여석에 돌아온다")
    void expiredHoldVisible() {
        Long seatId = seat();
        seatHoldService.hold(1L, SCHEDULE, seatId);

        clock.advance(Duration.ofMinutes(10));

        assertThat(selectable()).containsEntry(seatId, true);
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("예약으로 바꾼 좌석은 Redis 목록에서 빠지고 DB의 HOLD로 선택 불가·잔여석 제외다(두 번 빼지 않는다)")
    void convertedSeatCountedOnce() {
        Long converted = seat();
        seat();
        String holdId = seatHoldService.hold(1L, SCHEDULE, converted).holdId();
        conversionService.convert(1L, SCHEDULE, holdId);

        assertThat(selectable()).containsEntry(converted, false);
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("스위치가 DB면 Redis 선점을 읽지 않는다(지금 동작 그대로)")
    void dbModeIgnoresRedis() {
        Long seatId = seat();
        seatHoldService.hold(1L, SCHEDULE, seatId);
        modeSwitch.set(SeatHoldMode.DB);

        assertThat(selectable()).containsEntry(seatId, true);
        assertThat(remaining()).isEqualTo(1);
    }
}
