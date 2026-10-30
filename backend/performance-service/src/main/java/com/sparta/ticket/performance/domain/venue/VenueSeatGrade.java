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

/** 공연장별 좌석 등급(VIP, R 등). 가격은 공연별 등급 가격이 가진다. 명세는 .local/performance/tables.md 3.3이다. */
@Getter
@Entity
@Table(name = "p_venue_seat_grades")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueSeatGrade extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long venueId;

    @Column(nullable = false, length = 20)
    private String name;

    private VenueSeatGrade(Long venueId, String name) {
        this.venueId = venueId;
        this.name = name;
    }

    /** 공연장에 좌석 등급 생성 - 같은 공연장 안 좌석 등급 이름 중복 확인은 application이 한다.*/
    public static VenueSeatGrade create(Long venueId, String name) {
        return new VenueSeatGrade(venueId, name);
    }
}
