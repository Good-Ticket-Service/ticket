package com.sparta.ticket.performance.domain.performance;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
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
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 공연. 공연장 하나를 쓰고, 등급별 가격·판매 제외 좌석·회차를 가진다. 명세는 .local/performance/tables.md 3.5다.
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

    private Performance(
            Long sellerId,
            Long venueId,
            String title,
            Genre genre,
            AgeRating ageRating,
            int runningTime,
            String castInfo,
            String description,
            String posterUrl) {
        this.sellerId = sellerId;
        this.venueId = venueId;
        this.title = title;
        this.genre = genre;
        this.ageRating = ageRating;
        this.runningTime = runningTime;
        this.castInfo = castInfo;
        this.description = description;
        this.posterUrl = posterUrl;
        this.publicationStatus = PublicationStatus.DRAFT;
        this.operationStatus = OperationStatus.ACTIVE;
    }

    /** 공연 초안을 만든다. 공개 전(DRAFT)·운영 중(ACTIVE)으로 시작한다. 공백 정리·형식 검증은 요청 DTO에서 끝난 값이다. */
    public static Performance createDraft(
            Long sellerId,
            Long venueId,
            String title,
            Genre genre,
            AgeRating ageRating,
            int runningTime,
            String castInfo,
            String description,
            String posterUrl) {
        return new Performance(
                sellerId, venueId, title, genre, ageRating, runningTime, castInfo, description, posterUrl);
    }

    /** 본인 공연이 아니면 403 FORBIDDEN. */
    public void verifyOwner(Long userId) {
        if (!sellerId.equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "본인 공연만 변경할 수 있습니다.");
        }
    }

    /**
     * If-Match로 받은 version이 현재 값과 같은지 확인한다. 다르면 409 VERSION_CONFLICT.
     * Note: 비교는 공연 행을 잠근 뒤 해야 의미가 있다. 증가는 application이 잠금 모드로 한다.
     */
    public void verifyVersion(long expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new BusinessException(PerformanceErrorCode.VERSION_CONFLICT);
        }
    }

    /** 취소된 공연에는 회차를 추가할 수 없다. 409 INVALID_STATUS_TRANSITION. 공개 전·후는 따지지 않는다. */
    public void verifyNotCanceled() {
        if (operationStatus == OperationStatus.CANCELED) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "취소된 공연에는 회차를 추가할 수 없습니다.");
        }
    }

    /** 취소된 공연은 공개할 수 없다. 409 INVALID_STATUS_TRANSITION. 이미 공개된 뒤 취소된 공연도 같다. */
    public void verifyPublishable() {
        if (operationStatus == OperationStatus.CANCELED) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "취소된 공연은 공개할 수 없습니다.");
        }
    }

    public boolean isPublished() {
        return publicationStatus == PublicationStatus.PUBLISHED;
    }

    /** 공연 정보 중 공개에 필요한데 비어 있는 항목. 회차·좌석·가격 조건은 다른 테이블을 봐야 해서 application이 확인한다. */
    public List<PublicationRequirement> missingPublicationInfo() {
        List<PublicationRequirement> missing = new ArrayList<>();
        if (description == null || description.isBlank()) {
            missing.add(PublicationRequirement.DESCRIPTION);
        }
        if (posterUrl == null || posterUrl.isBlank()) {
            missing.add(PublicationRequirement.POSTER_URL);
        }
        return missing;
    }

    /**
     * 초안을 공개한다. 필수 조건 확인은 부른 쪽이 끝낸 상태다. publishedAt은 최초 공개 시각이며, 공개된 공연은 다시 부르지 않는다.
     * version은 커밋(flush) 때 @Version으로 오른다.
     */
    public void publish(LocalDateTime now) {
        this.publicationStatus = PublicationStatus.PUBLISHED;
        this.publishedAt = now;
    }

    /** 가격·판매 제외 같은 좌석 구성은 공개 전에만 바꿀 수 있다. 공개됐으면 409 PERFORMANCE_CONFIGURATION_LOCKED. */
    public void verifyConfigurable() {
        if (publicationStatus != PublicationStatus.DRAFT) {
            throw new BusinessException(PerformanceErrorCode.PERFORMANCE_CONFIGURATION_LOCKED);
        }
    }
}
