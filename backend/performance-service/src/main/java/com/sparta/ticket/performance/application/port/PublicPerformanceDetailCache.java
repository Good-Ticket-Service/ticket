package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import java.util.Optional;

/**
 * 공개 공연 상세 조회 DTO 캐시(이슈 #398). 구현은 infrastructure의 Redis 어댑터다.
 * 캐시는 원본이 아니라서 실패해도 예외를 올리지 않는다. 읽기 실패는 미적중으로, 저장·삭제 실패는 관측만 하고 넘어간다.
 */
public interface PublicPerformanceDetailCache {

    /** 없거나 읽지 못하면(Redis 장애·건너뛰기·역직렬화 실패) 빈 값이다. */
    Optional<PublicPerformanceDetail> find(Long performanceId);

    /** 성공한 조회 결과만 저장한다. 404는 저장하지 않는다. */
    void put(PublicPerformanceDetail detail);

    /** 상세 응답이 바뀌는 쓰기(회차 등록)의 커밋 뒤에 부른다. */
    void evict(Long performanceId);
}
