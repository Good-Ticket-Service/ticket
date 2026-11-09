package com.sparta.ticket.waitingroom.domain;

import com.sparta.ticket.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;

/**
 * 대기열 하나(room). 관리자가 보호할 범위와 시각을 정해 만들고, 생성 뒤에는 바뀌지 않는다.
 * 대기열은 대상 도메인을 모른다. scope는 해석하지 않고 입장 토큰에 실어 Gateway가 요청 URL과 비교한다.
 *
 * @param roomId 대기열 ID
 * @param scopes 보호할 범위. 예: {@code schedule:12}
 * @param openAt 줄을 받기 시작하는 시각
 * @param closeAt 대기열이 끝나는 시각. 이때부터 줄을 받지 않고 Gateway도 검사를 멈춘다(보호 끝)
 */
public record WaitingRoom(long roomId, List<String> scopes, Instant openAt, Instant closeAt) {

    public WaitingRoom {
        scopes = List.copyOf(scopes);
    }

    /**
     * 줄을 받을 수 있는지 확인한다. 오픈 전이면 409 WAITING_ROOM_NOT_OPEN, 끝났으면 409 WAITING_ROOM_CLOSED다.
     * 상태를 저장하지 않고 시각으로 판단하므로 오픈·종료 때 상태를 바꾸는 스케줄러가 없다.
     */
    public void verifyEnterable(Instant now) {
        if (now.isBefore(openAt)) {
            throw new BusinessException(QueueErrorCode.WAITING_ROOM_NOT_OPEN);
        }
        verifyNotClosed(now);
    }

    /** 끝나지 않았는지 확인한다. 순번 조회처럼 오픈 전에도 응답할 수 있는 요청에 쓴다. */
    public void verifyNotClosed(Instant now) {
        if (!now.isBefore(closeAt)) {
            throw new BusinessException(QueueErrorCode.WAITING_ROOM_CLOSED);
        }
    }
}
