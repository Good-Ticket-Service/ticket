package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.domain.schedule.Schedule;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 회차 명령(등록, 좌석 준비 상태 변경)의 저장·조회 포트. 모두 삭제되지 않은 회차만 본다. */
public interface ScheduleStore {

    Schedule save(Schedule schedule);

    /** 회차 행을 FOR UPDATE로 잠그고 읽는다. 좌석 준비 시도 시작과 결과 반영을 한 줄로 세운다. */
    Optional<Schedule> findByIdForUpdate(Long scheduleId);

    /** 공연의 회차 전체를 ID 순서로 FOR UPDATE로 잠그고 읽는다. 취소된 회차도 포함한다. 부모 공연을 잠근 뒤에 부른다. */
    List<Schedule> findByPerformanceIdForUpdate(Long performanceId);

    /** 같은 공연에 시작 시각이 같은 회차가 있는지. 부모 공연을 잠근 뒤에 부른다. */
    boolean existsByPerformanceIdAndStartAt(Long performanceId, LocalDateTime startAt);

    /** 좌석 준비가 PENDING이고 다음 시도 시각이 지난 회차 ID. 오래 기다린 것부터 최대 limit개. */
    List<Long> findSeatSetupDueIds(LocalDateTime now, int limit);

    /**
     * 회차의 좌석 준비 필드만 저장한다. 회차 version은 올리지 않는다(Schedule의 Note). 잠근 회차를 도메인 메서드로 바꾼 뒤 부른다.
     * Note: 이 회차는 이 트랜잭션에서 더 이상 Entity 변경 감지 대상이 아니다. 저장한 뒤에는 같은 객체를 다시 바꾸지 않는다.
     */
    void saveSeatSetup(Schedule schedule);
}
