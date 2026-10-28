package com.sparta.ticket.performance.domain.performance;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 공연. 공연장 하나를 쓰고, 등급별 가격·판매 제외 좌석·회차를 가진다. 명세는 .local/performance/tables.md 3.5다.
 * Note: 등록·수정·공개 API는 다음 작업에서 만든다. 상태 변경 메서드도 그때 추가한다.
 */
@Getter
@Entity
@Table(name = "p_performances")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Performance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 회원 서비스의 회원 ID. 요청 본문이 아니라 인증 주체에서 정한다. 공개 응답에 넣지 않는다. */
    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long venueId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Genre genre;

    @Convert(converter = AgeRatingConverter.class)
    @Column(nullable = false, length = 20)
    private AgeRating ageRating;

    @Column(nullable = false)
    private int runningTime;

    @Column(length = 500)
    private String castInfo;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String posterUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PublicationStatus publicationStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperationStatus operationStatus;

    /** 최초 공개 시각. 다시 공개를 요청해도 바꾸지 않는다. */
    @Column(columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime publishedAt;

    /** 공연 단위 명령(정보 수정, 가격, 판매 제외, 공개)의 If-Match 기준. 회차 변경으로는 오르지 않는다. */
    @Version
    @Column(nullable = false)
    private Long version;
}
