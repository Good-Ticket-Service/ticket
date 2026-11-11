package com.sparta.ticket.reservation.seat.application.port;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import java.util.function.Function;

/**
 * layout 응답 본문(직렬화한 바이트)을 (회차, configVersion) 단위로 한 번만 만들어 다시 쓰는 캐시(#287). 구현은 infrastructure의 서버
 * 메모리 캐시가 맡는다. 본문 형식(JSON, ApiResponse)은 모르고, 만드는 방법은 부르는 쪽(presentation)이 넘긴다.
 */
public interface SeatLayoutBodyCache {

    /**
     * 스냅샷의 (회차, configVersion)에 맞는 본문을 돌려준다. 없으면 render로 이 스냅샷의 본문을 만들어 저장한다.
     * - 같은 키의 동시 요청은 한 요청만 만들고, 나머지는 그 결과를 기다려 받는다.
     * - render가 예외를 던지면 저장하지 않고 그 예외를 그대로 던진다.
     *
     * <p>configVersion이 같으면 layout도 같다는 전제(좌석 준비는 바꾼 좌석에 새 configVersion을 쓴다)에 기댄다. 그래서 1초마다 새로
     * 만드는 스냅샷이 달라도 키가 같으면 처음 만든 본문을 준다.
     */
    byte[] get(SeatMapSnapshot snapshot, Function<SeatMapSnapshot, byte[]> render);
}