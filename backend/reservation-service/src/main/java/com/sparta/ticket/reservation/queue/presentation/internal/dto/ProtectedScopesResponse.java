package com.sparta.ticket.reservation.queue.presentation.internal.dto;

import com.sparta.ticket.reservation.queue.application.dto.WaitingRoomResult;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Gateway가 토큰을 검사할 대상 목록. 내부 API라 ApiResponse로 감싸지 않는다.
 *
 * @param rooms 끝나지 않은(closeAt 전) room 전부. 오픈 전 room도 들어 있다
 */
public record ProtectedScopesResponse(List<Room> rooms) {

    /**
     * @param scopes 보호하는 범위(예: schedule:12)
     * @param closeAt 보호가 끝나는 시각(+09:00). Gateway는 이 시각부터 검사하지 않는다
     */
    public record Room(List<String> scopes, OffsetDateTime closeAt) {}

    public static ProtectedScopesResponse from(List<WaitingRoomResult> rooms) {
        return new ProtectedScopesResponse(rooms.stream()
                .map(room -> new Room(room.scopes(), room.closeAt()))
                .toList());
    }
}
