package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.schedule.Schedule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 회차의 Spring Data 저장소. */
public interface ScheduleJpaRepository extends JpaRepository<Schedule, Long> {

    /** 삭제된 회차는 Entity의 @SQLRestriction으로 빠진다. */
    List<Schedule> findByPerformanceId(Long performanceId);
}
