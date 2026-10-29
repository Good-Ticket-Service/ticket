package com.sparta.ticket.performance.application;

import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import java.util.List;
import java.util.Optional;

/**
 * 일반 사용자용 회차 조회. 구현은 infrastructure의 QueryDSL 저장소가 맡는다.
 * 공개 공연의 기준은 공연 목록·상세와 같다: 공연이 PUBLISHED이고 공연·공연장이 삭제되지 않았다. 취소된 공연은 포함한다.
 */
public interface PublicScheduleReader {

    /** 공개 공연이 있으면 true. 없거나 초안·삭제된 공연, 삭제된 공연장의 공연은 false다. */
    boolean existsPublicPerformance(Long performanceId);

    /** 공개 공연의 회차. 삭제 회차는 빼고 취소 회차는 넣는다. 시작 시각 순, 같으면 ID 순이다. */
    List<PublicScheduleRow> findSchedules(Long performanceId);

    /** 공개 공연에 속한 회차 하나. 회차가 삭제됐거나 부모 공연이 공개 공연이 아니면 empty다. 취소 회차는 돌려준다. */
    Optional<PublicScheduleRow> findSchedule(Long scheduleId);
}
