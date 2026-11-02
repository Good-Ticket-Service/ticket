package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleCancellationRepository {

    /** 취소 기록을 남긴다. 이미 있으면 처음 기록을 그대로 두고 0행이다. 같은 이벤트가 다시 와도 결과가 같다. */
    int recordIfAbsent(Long scheduleId, String eventId, LocalDateTime canceledAt, LocalDateTime receivedAt);

    /** 이 회차의 취소 기록이 있는가. 완료한 기록도 포함한다. 취소된 회차는 다시 열리지 않는다. */
    boolean existsById(Long scheduleId);

    /** 정리가 끝나지 않은 회차를 받은 순서로 고른다. */
    List<Long> findUnfinishedScheduleIds(int limit);

    /** 기록을 완료한다. receivedBefore 이전에 받았고 끝나지 않은 기록일 때만 1행이다. */
    int complete(Long scheduleId, LocalDateTime receivedBefore, LocalDateTime completedAt);
}
