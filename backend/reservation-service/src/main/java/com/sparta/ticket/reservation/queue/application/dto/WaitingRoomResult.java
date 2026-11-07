package com.sparta.ticket.reservation.queue.application.dto;

import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 대기열(room) 생성·조회 결과.
 *
 * @param roomId 대기열 ID. 진입·순번 조회 경로에 넣는다
 * @param scopes 보호하는 범위
 * @param openAt 줄을 받기 시작하는 시각(+09:00). 프런트는 이 시각 전까지 오픈 전 안내를 한다
 * @param closeAt 대기열이 끝나는 시각(+09:00)
 */
public record WaitingRoomResult(long roomId, List<String> scopes, OffsetDateTime openAt, OffsetDateTime closeAt) {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    public static WaitingRoomResult from(WaitingRoom room) {
        return new WaitingRoomResult(
                room.roomId(),
                room.scopes(),
                room.openAt().atOffset(KST),
                room.closeAt().atOffset(KST));
    }
}
