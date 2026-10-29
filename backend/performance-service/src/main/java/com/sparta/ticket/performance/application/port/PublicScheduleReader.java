package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.PublicScheduleRow;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import java.util.List;
import java.util.Optional;

/**
 * 일반 사용자용 회차 조회. 구현은 infrastructure의 QueryDSL 저장소가 맡는다.
 * 공개 공연의 기준은 공연 목록·상세와 같다: 공연이 PUBLISHED이고 공연·공연장이 삭제되지 않았다. 취소된 공연은 포함한다.
 */
public interface PublicScheduleReader {

    /** 공개 공연의 운영 상태. 없거나 초안·삭제된 공연, 삭제된 공연장의 공연은 empty다. */
    Optional<OperationStatus> findPublicPerformanceStatus(Long performanceId);

    /** 공연의 회차. 부모 공연이 공개 공연인지는 보지 않는다. 삭제 회차는 빼고 취소 회차는 넣는다. 시작 시각 순, 같으면 ID 순이다. */
    List<PublicScheduleRow> findSchedules(Long performanceId);

    /** 삭제되지 않은 회차 하나. 부모 공연이 공개 공연인지는 보지 않는다. 취소 회차는 돌려준다. */
    Optional<PublicScheduleRow> findSchedule(Long scheduleId);
}
