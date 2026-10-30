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

    private VenueSeat(Long venueId, Long venueSeatGradeId, String section, String rowLabel, int seatNumber) {
        this.venueId = venueId;
        this.venueSeatGradeId = venueSeatGradeId;
        this.section = section;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
    }
    /** 등급이 지정된 공연장 좌석 생성 - 등급 소속, 위치, 중복 확인은 application이 한다*/
    public static VenueSeat create(
            Long venueId, Long venueSeatGradeId, String section, String rowLabel, int seatNumber) {
        return new VenueSeat(venueId, venueSeatGradeId, section, rowLabel, seatNumber);
    }

    /** 같은 공연장 안에서 좌석을 구분하는 위치(구역, 열, 번호)*/
    public SeatPosition position() {
        return new SeatPosition(section, rowLabel, seatNumber);
    }
}
