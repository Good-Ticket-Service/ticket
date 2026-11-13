package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.PublicScheduleList;
import java.util.Optional;

/** 캐시 실패는 미적중으로 처리하고, 저장·삭제 실패는 업무 응답을 바꾸지 않는다. */
public interface PublicScheduleListCache {
    Optional<PublicScheduleList> find(Long performanceId);

    void put(Long performanceId, PublicScheduleList schedules);

    void evict(Long performanceId);
}
