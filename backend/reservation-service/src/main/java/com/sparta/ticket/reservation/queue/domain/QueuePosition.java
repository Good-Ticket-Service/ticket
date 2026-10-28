package com.sparta.ticket.reservation.queue.domain;

/**
 * 대기 줄에서의 위치.
 *
 * @param aheadCount 내 앞에 선 인원. 맨 앞이면 0이다
 */
public record QueuePosition(long aheadCount) {

    /** 1부터 세는 내 순번. */
    public long position() {
        return aheadCount + 1;
    }
}
