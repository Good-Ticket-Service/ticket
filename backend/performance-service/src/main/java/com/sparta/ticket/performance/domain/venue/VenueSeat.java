package com.sparta.ticket.performance.domain.venue;

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
 * 공연장 물리 좌석. 등급은 공연장에 고정되어, 이 공연장을 쓰는 모든 공연·회차에서 같다.
 * 명세는 .local/performance/tables.md 3.4다.
 */
@Getter
@Entity
@Table(name = "p_venue_seats")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueSeat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long venueId;

    @Column(nullable = false)
    private Long venueSeatGradeId;

    @Column(nullable = false, length = 20)
    private String section;

    @Column(nullable = false, length = 10)
    private String rowLabel;

    @Column(nullable = false)
    private int seatNumber;
}
