package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldModeSwitch;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 회차 좌석 배치도 스냅샷을 DB에서 새로 만든다. 스냅샷 캐시(SelectableSeatCache 구현)가 처음 로딩과 refresh에 쓴다(#311).
 * 처음 로딩·만료 뒤 로딩은 요청 스레드에서, refresh는 캐시 전용 스레드에서 실행된다. 어느 쪽이든 load 지표에 남는다.
 *
 * REDIS 모드(#449)에서는 Redis로 잡힌 좌석이 DB에서 AVAILABLE이라, DB로 만든 판정 위에 Redis 선점 좌석을 선택 불가로 덮는다.
 * 목록·구역·status가 모두 이 스냅샷에서 나오므로 함께 맞는다. Redis를 읽지 못하면 DB 판정만으로 만든다. 화면 표시용이고,
 * 잡힌 좌석을 누르면 선점 API가 409로 막는다.
 *
 * Note: 이 클래스에는 트랜잭션을 두지 않는다. 읽기 전용 트랜잭션은 SelectableSeatQueryService가 열고, Redis는 그 트랜잭션이 끝난 뒤
 * 읽는다(DB 연결을 Redis 호출 동안 잡지 않는다). 비트맵도 그 뒤에 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatMapSnapshotLoader {

    private final SelectableSeatQueryService queryService;
    private final SeatSnapshotMetrics metrics;
    private final SeatHoldModeSwitch modeSwitch;
    private final SeatHoldStore holdStore;
    private final Clock clock;

    /** 좌석이 없는 회차는 404 SEAT_NOT_FOUND를 던진다. 캐시는 예외를 저장하지 않는다. */
    public SeatMapSnapshot load(Long scheduleId) {
        return metrics.timeLoad(() -> SeatMapSnapshot.of(withRedisHolds(queryService.getSelectableSeats(scheduleId))));
    }

    private SelectableSeatsResult withRedisHolds(SelectableSeatsResult result) {
        if (modeSwitch.current() != SeatHoldMode.REDIS) {
            return result;
        }
        Set<Long> held;
        try {
            held = holdStore.heldSeatIds(result.scheduleId(), clock.instant());
        } catch (BusinessException e) {
            log.warn("Redis 선점 좌석을 읽지 못해 DB 판정만으로 좌석 배치도를 만든다. scheduleId={}", result.scheduleId());
            return result;
        }
        if (held.isEmpty()) {
            return result;
        }
        List<SelectableSeatResult> seats = result.seats().stream()
                .map(seat -> seat.selectable() && held.contains(seat.scheduleSeatId()) ? unselectable(seat) : seat)
                .toList();
        return new SelectableSeatsResult(result.scheduleId(), result.configVersion(), result.asOf(), seats);
    }

    private static SelectableSeatResult unselectable(SelectableSeatResult seat) {
        return new SelectableSeatResult(
                seat.scheduleSeatId(),
                seat.venueSeatId(),
                seat.section(),
                seat.rowLabel(),
                seat.seatNumber(),
                seat.venueSeatGradeId(),
                seat.gradeName(),
                seat.basePrice(),
                false);
    }
}
