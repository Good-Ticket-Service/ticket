package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeats;
import java.util.function.Supplier;

/** 등급별 잔여석 집계 결과의 캐시. 구현은 infrastructure의 서버 메모리 캐시가 맡는다. */
public interface RemainingSeatCache {

    /**
     * 캐시에 있으면 그 값을, 없으면 loader로 집계해 저장하고 돌려준다.
     * 같은 회차를 동시에 요청해도 loader는 한 번만 실행되고 나머지 요청은 그 결과를 받는다.
     * loader가 예외를 던지면 저장하지 않고 그 예외를 그대로 던진다. 다음 요청이 다시 집계한다.
     */
    Lookup getOrLoad(Long scheduleId, Supplier<RemainingSeats> loader);

    /**
     * @param cached 이번 호출이 loader를 실행하지 않았으면 true
     */
    record Lookup(RemainingSeats seats, boolean cached) {}
}
