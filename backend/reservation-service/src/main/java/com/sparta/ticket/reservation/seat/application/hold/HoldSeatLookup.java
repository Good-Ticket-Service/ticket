package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.reservation.seat.application.port.HoldSeatCache;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatHoldSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * REDIS 모드(#459)의 선점이 DB 좌석 대신 읽는 좌석 고정 정보. 회차의 좌석을 한 번 읽어 서버 메모리에 둔다.
 * 점유(상태·예약)는 담지 않는다. 점유는 Redis가 판정한다.
 */
@Component
@RequiredArgsConstructor
public class HoldSeatLookup {

    private final HoldSeatCache cache;
    private final ScheduleSeatRepository scheduleSeatRepository;

    /**
     * 이 회차의 좌석이면 고정 정보, 아니면(다른 회차·없는 좌석·삭제된 좌석) 빈 값이다.
     * 캐시에 없으면 DB를 한 번 읽는다. 캐시를 채운 뒤 좌석이 생겼는데 이 서버의 캐시가 비워지지 않은 경우(좌석 준비는 그 준비를 처리한
     * 서버의 캐시만 비운다)에 404를 내지 않기 위해서다. DB에 있으면 이 회차 캐시를 비워 다음 선점이 다시 읽게 한다.
     */
    public Optional<SeatHoldSnapshot> find(Long scheduleId, Long scheduleSeatId) {
        SeatHoldSnapshot cached = seats(scheduleId).get(scheduleSeatId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SeatHoldSnapshot> read = scheduleSeatRepository
                .findForHold(scheduleSeatId, scheduleId)
                .map(seat -> SeatHoldSnapshot.fixed(
                        seat.scheduleSeatId(), seat.venueSeatId(), seat.configVersion(), seat.sellable(), seat.basePrice()));
        if (read.isPresent()) {
            cache.invalidate(scheduleId);
        }
        return read;
    }

    /** 회차를 미리 읽어 둔다. 판매 전·측정 준비에 부른다. */
    public void warm(Long scheduleId) {
        seats(scheduleId);
    }

    public void invalidate(Long scheduleId) {
        cache.invalidate(scheduleId);
    }

    private Map<Long, SeatHoldSnapshot> seats(Long scheduleId) {
        return cache.getOrLoad(scheduleId, () -> {
            Map<Long, SeatHoldSnapshot> seats = new HashMap<>();
            for (ScheduleSeat seat : scheduleSeatRepository.findAllBySchedule(scheduleId)) {
                seats.put(
                        seat.getId(),
                        SeatHoldSnapshot.fixed(
                                seat.getId(),
                                seat.getVenueSeatId(),
                                seat.getConfigVersion(),
                                seat.isSellable(),
                                seat.getBasePrice()));
            }
            return Map.copyOf(seats);
        });
    }
}
