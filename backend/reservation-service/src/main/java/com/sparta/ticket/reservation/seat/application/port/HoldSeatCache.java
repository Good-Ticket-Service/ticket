package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.domain.SeatHoldSnapshot;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 선점에 쓰는 회차 좌석 고정 정보(좌석 ID → 고정 정보)의 캐시(#449 후속). 구현은 infrastructure의 서버 메모리 캐시가 맡는다.
 * 고정 정보는 판매 중에 바뀌지 않아(architecture 10절) 회차마다 한 번 읽는다.
 */
public interface HoldSeatCache {

    /**
     * 캐시에 있으면 그 값을, 없으면 loader로 읽어 저장하고 돌려준다. 같은 회차를 동시에 요청해도 loader는 한 번만 실행된다.
     * loader가 예외를 던지면 저장하지 않는다.
     */
    Map<Long, SeatHoldSnapshot> getOrLoad(Long scheduleId, Supplier<Map<Long, SeatHoldSnapshot>> loader);

    /** 회차의 값을 지운다. 좌석 구성이 바뀌었을 때 부른다. */
    void invalidate(Long scheduleId);
}
