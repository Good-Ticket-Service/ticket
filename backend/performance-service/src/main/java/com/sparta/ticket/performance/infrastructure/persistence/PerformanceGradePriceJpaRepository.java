package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.PerformanceGradePrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 공연별 등급 가격의 Spring Data 저장소. */
public interface PerformanceGradePriceJpaRepository extends JpaRepository<PerformanceGradePrice, Long> {

    /** 삭제된 가격은 Entity의 @SQLRestriction으로 빠진다. */
    List<PerformanceGradePrice> findByPerformanceId(Long performanceId);
}
