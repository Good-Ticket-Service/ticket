package com.sparta.ticket.performance.domain.schedule;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import jakarta.persistence.Column;
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
 * 공연 회차. 공연의 공연장·등급·가격·판매 제외를 그대로 쓰고, 회차별로는 시각과 운영·좌석 준비 상태만 가진다.
 * 좌석별 선점·판매 상태는 예매 서비스가 관리한다. 명세는 .local/performance/tables.md 3.7이다.
 * Note: 회차 취소와 삭제는 다르다. 취소는 operationStatus=CANCELED, 삭제는 deleted_at이다.
 */
@Getter
@Entity
@Table(name = "p_schedules")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false, columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime startAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime bookingOpenAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime bookingCloseAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperationStatus operationStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatSetupStatus seatSetupStatus;

    /** 좌석 구성(가격, 판매 제외, 공연장)이 바뀌면 오른다. 예매 서비스의 좌석 스냅샷과 비교한다. */
    @Column(nullable = false)
    private long seatConfigVersion;

    /** 현재 좌석 준비 요청 UUID. 같은 구성의 재전송은 같은 값을 쓴다. */
    @Column(nullable = false, length = 36)
    private String seatSetupRequestId;

    @Column(length = 500)
    private String seatSetupError;

    @Column(nullable = false)
    private boolean salesSuspended;

    @Column(length = 500)
    private String suspensionReason;

    @Column(columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime canceledAt;

    @Column(length = 500)
    private String cancelReason;

    @Column(nullable = false)
    private int seatSetupAttempts;

    @Column(columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime seatSetupNextAttemptAt;

    /** 회차 명령(수정, 삭제, 취소, 판매 중지)의 If-Match 기준. 부모 공연의 version과 별개다. */
    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * 공연의 좌석 구성(가격, 판매 제외)이 바뀌어 좌석을 다시 준비해야 한다. 구성 버전을 올리고 새 요청 ID로 PENDING부터 시작한다.
     * Note: 구성 버전이 오를 때만 새 requestId를 쓴다(#54). 같은 구성의 재전송·자동 재시도는 기존 requestId를 쓴다.
     * READY·FAILED였던 회차도 다시 준비한다. 좌석 준비 호출은 커밋 뒤 회차 등록 연동(이슈 #131)이 맡는다.
     */
    public void requireSeatSetupForNewConfig(String newRequestId) {
        this.seatConfigVersion++;
        this.seatSetupStatus = SeatSetupStatus.PENDING;
        this.seatSetupRequestId = newRequestId;
        this.seatSetupError = null;
        this.seatSetupAttempts = 0;
        this.seatSetupNextAttemptAt = null;
    }
}
