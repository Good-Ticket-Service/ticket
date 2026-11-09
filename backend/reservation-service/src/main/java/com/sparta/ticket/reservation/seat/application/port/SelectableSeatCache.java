package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;

/**
 * 회차별 좌석 배치도(선택 가능 좌석) 스냅샷 캐시. 구현은 infrastructure의 서버 메모리 캐시가 맡고, 스냅샷은 SeatMapSnapshotLoader로 만든다.
 */
public interface SelectableSeatCache {

    /**
     * 회차 스냅샷을 돌려준다.
     * - 없거나 최대 보관 시간(cache-max-age)이 지났으면 요청 스레드에서 만든다. 같은 회차 동시 요청은 한 번만 만들고 그 결과를 나눠 받는다.
     * - 만든 지 cache-refresh가 지났으면 기존 스냅샷을 바로 돌려주고, 전용 스레드가 뒤에서 다시 만든다(#311).
     * 요청 스레드에서 만들다 예외가 나면 저장하지 않고 그 예외를 그대로 던진다(404 SEAT_NOT_FOUND 포함). 다음 요청이 다시 만든다.
     */
    SeatMapSnapshot get(Long scheduleId);
}
