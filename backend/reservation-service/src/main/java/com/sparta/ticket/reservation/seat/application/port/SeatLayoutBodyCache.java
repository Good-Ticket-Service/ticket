package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.application.dto.SeatLayoutBody;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import java.util.function.Function;

/**
 * layout 응답 본문 한 벌(원본과 미리 압축한 gzip, SeatLayoutBody)을 (회차, configVersion) 단위로 한 번만 만들어 다시 쓰는 캐시(#287).
 * 구현은 infrastructure의 서버 메모리 캐시가 맡는다. 본문 형식(JSON, ApiResponse)은 모르고, 만드는 방법은 부르는 쪽이 넘긴다.
 */
public interface SeatLayoutBodyCache {

    /**
     * 스냅샷의 (회차, configVersion)에 맞는 본문을 돌려준다. 없으면 render로 이 스냅샷의 원본(JSON 바이트)을 만들어 저장한다.
     * - 압축본은 요청이 기다리지 않게 뒤에서 만들어 바꿔 넣는다. 그 전에는 gzip이 null인 본문을 돌려주고, 부르는 쪽은 원본을 보낸다.
     * - 같은 키의 동시 요청은 한 요청만 만들고, 나머지는 그 결과를 기다려 받는다.
     * - render가 예외를 던지면 저장하지 않고 그 예외를 그대로 던진다.
     *
     * <p>configVersion이 같으면 layout도 같다는 전제(좌석 준비는 바꾼 좌석에 새 configVersion을 쓴다)에 기댄다. 그래서 1초마다 새로
     * 만드는 스냅샷이 달라도 키가 같으면 처음 만든 본문을 준다.
     */
    SeatLayoutBody get(SeatMapSnapshot snapshot, Function<SeatMapSnapshot, byte[]> render);
}
