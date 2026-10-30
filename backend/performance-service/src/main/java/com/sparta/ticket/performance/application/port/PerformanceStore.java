package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.performance.PerformanceGradePrice;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 판매자 공연 명령(초안 등록, 등급별 가격)의 저장·조회 포트. 구현은 infrastructure가 맡는다. 모두 삭제되지 않은 행만 본다. */
public interface PerformanceStore {

    Performance save(Performance performance);

    /** 공연을 행 잠금(FOR UPDATE)으로 읽는다. 같은 공연의 구성 변경·공개·회차 등록을 한 줄로 세운다. */
    Optional<Performance> findByIdForUpdate(Long performanceId);

    /** 공연 version을 1 올리고 오른 값을 돌려준다. findByIdForUpdate로 잠근 뒤에만 부른다. */
    long increaseVersion(Long performanceId);

    /** 공연의 등급 가격 전체. */
    List<PerformanceGradePrice> findGradePrices(Long performanceId);

    /** 새 가격 행을 저장한다. 기존 행의 가격 변경·논리 삭제는 Entity 변경으로 커밋 때 반영된다. */
    void saveGradePrices(List<PerformanceGradePrice> prices);

    /** 공연장의 좌석 중 이 공연에서 판매 제외되지 않은 좌석이 쓰는 등급 ID. 가격이 반드시 있어야 하는 등급이다. */
    Set<Long> findSellableGradeIds(Long performanceId, Long venueId);
}
