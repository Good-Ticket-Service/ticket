package com.sparta.ticket.performance.domain.performance;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 공연별 등급 가격. 공연장의 고정 등급에 가격만 붙이며 같은 공연의 모든 회차가 공유한다.
 * 명세는 .local/performance/tables.md 3.6이다.
 */
@Getter
@Entity
@Table(name = "p_performance_grade_prices")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerformanceGradePrice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false)
    private Long venueSeatGradeId;

    /** 할인 전 원화 금액. 0원 가격은 저장하지 않는다. */
    @Column(nullable = false)
    private int price;
}
