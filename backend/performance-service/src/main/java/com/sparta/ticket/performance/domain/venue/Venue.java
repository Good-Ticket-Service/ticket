package com.sparta.ticket.performance.domain.venue;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
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

    private Venue(String name, String address, String region) {
        this.name = name;
        this.address = address;
        this.region = region;
    }

    /**공연장 생성- 공백 정리, 길이 검증은 요청 DTO에서 끝난 값*/
    public static Venue create(String name, String address, String region) {
        return new Venue(name, address, region);
    }

    /**
     * If-Match로 받은 version이 현재 값과 같은지 확인 - 다르면 409 VERSION_CONFLICT
     * Note: 비교는 공연장 행을 잠근 뒤 해야 의미가 있다. 증가는 application이 잠금 모드로 한다.
     * */
    public void verifyVersion(long expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new BusinessException(PerformanceErrorCode.VERSION_CONFLICT);
        }
    }
}
