package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import java.util.function.Supplier;

/** 회차별 좌석 배치도(선택 가능 좌석) 스냅샷 캐시. 구현은 infrastructure의 서버 메모리 캐시가 맡는다. */
public interface SelectableSeatCache {

    /**
     * 캐시에 있으면 그 스냅샷을, 없으면 loader로 만들어 저장하고 돌려준다.
     * 같은 회차를 동시에 요청해도 loader는 한 번만 실행되고 나머지 요청은 그 결과를 받는다.
     * loader가 예외를 던지면 저장하지 않고 그 예외를 그대로 던진다. 다음 요청이 다시 만든다.
     */
    SelectableSeatsResult getOrLoad(Long scheduleId, Supplier<SelectableSeatsResult> loader);
}
