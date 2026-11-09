package com.sparta.ticket.waitingroom.application.dto;

import com.sparta.ticket.waitingroom.domain.QueueStatus;
import java.time.OffsetDateTime;

/**
 * 진입·순번 조회 결과. 클라이언트는 status로 나눠 읽는다.
 * 순번 필드는 숫자로 늘 채워 클라이언트가 null을 다루지 않게 하고, 입장하면 줄에 없으므로 0이다. 입장 토큰 필드는 대기 중에 줄 값이 없어 null이다.
 *
 * @param status 내 대기 상태
 * @param position 1부터 세는 내 순번. ENTERED면 0이다
 * @param aheadCount 내 앞에 선 인원. ENTERED면 0이다
 * @param estimatedWaitSeconds 예상 대기 시간(초). 앞 인원 ÷ 초당 입장 인원을 올림한 값이다. ENTERED면 0이다
 * @param entryToken 입장 토큰. 좌석 조회·선점 요청의 X-Queue-Token 헤더에 넣는다. ENTERED일 때만 있다
 * @param entryExpiresAt 입장 토큰 만료 시각(+09:00). ENTERED일 때만 있다
 */
public record QueueStatusResult(
        QueueStatus status,
        long position,
        long aheadCount,
        long estimatedWaitSeconds,
        String entryToken,
        OffsetDateTime entryExpiresAt) {

    public static QueueStatusResult waiting(long position, long aheadCount, long estimatedWaitSeconds) {
        return new QueueStatusResult(QueueStatus.WAITING, position, aheadCount, estimatedWaitSeconds, null, null);
    }

    public static QueueStatusResult entered(String entryToken, OffsetDateTime entryExpiresAt) {
        return new QueueStatusResult(QueueStatus.ENTERED, 0, 0, 0, entryToken, entryExpiresAt);
    }
}
