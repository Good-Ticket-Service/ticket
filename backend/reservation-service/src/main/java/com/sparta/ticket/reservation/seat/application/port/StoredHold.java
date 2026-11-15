package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldState;
import java.time.Instant;

/**
 * Redis에 둔 선점 하나(#449). 결제하기 때 DB 예약을 만들 재료를 모두 갖는다.
 *
 * @param validation 선점 때 받은 판매 검증 결과. 결제하기 때 다시 부르지 않고 이 값으로 예약을 만든다(검증은 선점 때 한 번, DB 방식과 같은 의미)
 * @param expiresAt 선점 만료 시각. Redis 키 만료와 결제하기 때 DB hold_expires_at에 같은 값을 쓴다
 * @param reservationId state가 CONVERTED일 때만 있다
 */
public record StoredHold(
        String holdId,
        Long scheduleId,
        Long userId,
        Long scheduleSeatId,
        SaleValidation validation,
        Instant expiresAt,
        HoldState state,
        Long reservationId) {

    public int basePrice() {
        return validation.seats().get(0).basePrice();
    }
}
