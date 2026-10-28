package com.sparta.ticket.performance.domain.venue;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 공연장. 좌석 등급과 물리 좌석의 원본이며, 공연은 공연장 하나를 쓴다. 명세는 .local/performance/tables.md 3.2다.
 * Note: 등록 API는 다음 작업에서 만든다. 그 전까지 데이터는 시드 SQL로 넣는다.
 */
@Getter
@Entity
@Table(name = "p_venues")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Venue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, length = 20)
    private String region;

    /** 공연장·등급·좌석 변경의 If-Match 기준. */
    @Version
    @Column(nullable = false)
    private Long version;
}
