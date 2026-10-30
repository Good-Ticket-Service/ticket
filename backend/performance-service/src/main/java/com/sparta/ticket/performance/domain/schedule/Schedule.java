package com.sparta.ticket.performance.domain.schedule;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
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

    /*
     * Note: 좌석 준비 필드(seatSetup*, seatConfigVersion)는 바뀌어도 version을 올리지 않는다. version은 판매자·관리자의 회차 명령
     * If-Match 기준인데, 서버가 좌석 준비를 진행할 때마다 오르면 판매자가 받은 ETag가 이유 없이 무효가 된다.
     * 그래서 이 필드는 Entity 변경 감지가 아니라 ScheduleStore.saveSeatSetup(JPQL UPDATE)으로 저장한다. 변경 감지로 저장하면
     * 감사 컬럼(updated_at)이 함께 바뀌어 version이 오른다. 이 필드를 바꾸는 쪽은 모두 회차 행을 FOR UPDATE로 잠그고 바꾼다.
     */
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

    private Schedule(
            Long performanceId,
            LocalDateTime startAt,
            LocalDateTime bookingOpenAt,
            LocalDateTime bookingCloseAt,
            String seatSetupRequestId,
            LocalDateTime now) {
        this.performanceId = performanceId;
        this.startAt = startAt;
        this.bookingOpenAt = bookingOpenAt;
        this.bookingCloseAt = bookingCloseAt;
        this.operationStatus = OperationStatus.ACTIVE;
        this.seatSetupStatus = SeatSetupStatus.PENDING;
        this.seatConfigVersion = 1;
        this.seatSetupRequestId = seatSetupRequestId;
        this.salesSuspended = false;
        this.seatSetupAttempts = 0;
        this.seatSetupNextAttemptAt = now;
    }

    /**
     * 새 회차를 만든다. 좌석 준비는 PENDING·구성 버전 1에서 시작하고, 다음 시도 시각을 지금으로 둬 바로 보낼 수 있게 한다.
     * 시각 순서는 verifyTimeRange로 먼저 확인한 값이다.
     */
    public static Schedule register(
            Long performanceId,
            LocalDateTime startAt,
            LocalDateTime bookingOpenAt,
            LocalDateTime bookingCloseAt,
            String seatSetupRequestId,
            LocalDateTime now) {
        verifyTimeRange(startAt, bookingOpenAt, bookingCloseAt);
        return new Schedule(performanceId, startAt, bookingOpenAt, bookingCloseAt, seatSetupRequestId, now);
    }

    /** 예매 오픈 < 예매 마감 ≤ 공연 시작이 아니면 400 INVALID_TIME_RANGE. DB를 보지 않으므로 공연을 잠그기 전에 부른다. */
    public static void verifyTimeRange(
            LocalDateTime startAt, LocalDateTime bookingOpenAt, LocalDateTime bookingCloseAt) {
        if (!bookingOpenAt.isBefore(bookingCloseAt) || bookingCloseAt.isAfter(startAt)) {
            throw new BusinessException(
                    PerformanceErrorCode.INVALID_TIME_RANGE, "회차 시각은 예매 오픈 < 예매 마감 ≤ 공연 시작이어야 합니다.");
        }
    }

    /**
     * 공연의 좌석 구성(가격, 판매 제외)이 바뀌어 좌석을 다시 준비해야 한다. 구성 버전을 올리고 새 요청 ID로 PENDING부터 시작한다.
     * Note: 구성 버전이 오를 때만 새 requestId를 쓴다(#54). 같은 구성의 재전송·자동 재시도는 기존 requestId를 쓴다.
     * READY·FAILED였던 회차도 다시 준비한다. 다음 시도 시각을 지금으로 둬 커밋 뒤 바로 보낼 수 있게 한다.
     */
    public void requireSeatSetupForNewConfig(String newRequestId, LocalDateTime now) {
        this.seatConfigVersion++;
        this.seatSetupStatus = SeatSetupStatus.PENDING;
        this.seatSetupRequestId = newRequestId;
        this.seatSetupError = null;
        this.seatSetupAttempts = 0;
        this.seatSetupNextAttemptAt = now;
    }

    /**
     * PENDING이고 다음 시도 시각이 지났는지. 지나지 않았으면 다른 실행이 시도 중(임대 시간 안)이거나 재시도 간격을 기다리는 중이다.
     */
    public boolean isSeatSetupDue(LocalDateTime now) {
        return seatSetupStatus == SeatSetupStatus.PENDING
                && (seatSetupNextAttemptAt == null || !seatSetupNextAttemptAt.isAfter(now));
    }

    /**
     * 좌석 준비를 한 번 보내도 되는지 확인하고, 보낸다면 시도 횟수를 올린다. 보내지 않으면 false.
     * Note: 다음 시도 시각을 지금 + retryDelay로 미리 옮겨 둔다. 호출하는 동안 다른 스레드·서버의 재시도 스케줄러가 같은 회차를
     * 다시 보내지 않게 하는 임대 시간이다. 그래서 retryDelay는 한 번 호출의 최대 시간(connect + read 타임아웃)보다 길어야 한다.
     */
    public boolean startSeatSetupAttempt(LocalDateTime now, Duration retryDelay) {
        if (!isSeatSetupDue(now)) {
            return false;
        }
        this.seatSetupAttempts++;
        this.seatSetupNextAttemptAt = now.plus(retryDelay);
        return true;
    }

    /** 결과가 지금 회차의 좌석 준비 요청에 대한 것인지. 다르면 지난 요청의 결과라 반영하지 않는다(architecture 10절). */
    public boolean isCurrentSeatSetup(String requestId, long configVersion) {
        return seatSetupStatus == SeatSetupStatus.PENDING
                && seatSetupRequestId.equals(requestId)
                && seatConfigVersion == configVersion;
    }

    public void completeSeatSetup() {
        this.seatSetupStatus = SeatSetupStatus.READY;
        this.seatSetupError = null;
        this.seatSetupNextAttemptAt = null;
    }

    /** 다시 보내도 같은 결과라 멈춘다. error는 판매자에게 보이는 안전한 요약이다(내부 오류 내용을 넣지 않는다). */
    public void failSeatSetup(String error) {
        this.seatSetupStatus = SeatSetupStatus.FAILED;
        this.seatSetupError = error;
        this.seatSetupNextAttemptAt = null;
    }
}
