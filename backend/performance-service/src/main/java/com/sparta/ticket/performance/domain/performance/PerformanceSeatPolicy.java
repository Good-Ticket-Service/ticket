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
 * 공연 공통 판매 제외 좌석. 행이 있으면 그 공연의 모든 회차에서 판매하지 않는다.
 * 제외 사유는 관리용이며 공개 응답에 넣지 않는다. 명세는 .local/performance/tables.md 3.8이다.
 */
@Getter
@Entity
@Table(name = "p_performance_seat_policies")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerformanceSeatPolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false)
    private Long venueSeatId;

    @Column(nullable = false, length = 255)
    private String exclusionReason;
}
