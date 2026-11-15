package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Redis 선점 조회(GET .../holds/{holdId}, #449). 결제 화면이 결제하기 전에 주기적으로 부른다. Redis만 읽고 DB는 읽지 않는다.
 * 상태는 Redis 기준이다. 예약으로 바꾼 뒤의 예약 상태(결제 중·해제 등)는 예약 조회 API로 본다.
 */
@Service
@RequiredArgsConstructor
public class SeatHoldQueryService {

    private final SeatHoldStore store;
    private final Clock clock;

    /** 없거나(만료·해제) 남의 선점이면 404 HOLD_NOT_FOUND다. 남의 선점인지 드러내지 않는다. */
    public SeatHoldView get(Long userId, Long scheduleId, String holdId) {
        if (!HoldIds.isHoldId(holdId)) {
            throw new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND);
        }
        StoredHold hold = store.find(scheduleId, holdId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND));
        return new SeatHoldView(hold, LocalDateTime.ofInstant(hold.expiresAt(), clock.getZone()));
    }
}
