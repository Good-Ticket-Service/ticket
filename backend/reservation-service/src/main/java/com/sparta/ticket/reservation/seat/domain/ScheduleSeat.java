package com.sparta.ticket.reservation.seat.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 회차 좌석. 공연장 좌석의 위치·등급·가격을 회차마다 복사해 두고, 이 회차의 선점·판매 상태를 따로 관리한다.
 * 상태는 아래 전이 메서드로만 바뀐다. 허용 전이는 Notion 테이블 명세서 schedule_seat 절의 "컬럼이 바뀌는 시점" 표를 따른다.
 *
 * Note: 전이 메서드는 좌석 행을 FOR UPDATE로 잠근 뒤 부른다. 잠그지 않고 읽은 상태로 판단하면 그 사이 다른 트랜잭션이 바꿨을 수 있다.
 * booking이 부르는 SeatFacade.sell·release는 잠금 없이 조건부 UPDATE로 바꾸지만, 바뀌는 값은 이 메서드와 같다.
 */
@Getter
@Entity
@Table(name = "p_schedule_seats")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleSeat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long venueSeatId;

    @Column(nullable = false)
    private Long venueSeatGradeId;

    @Column(nullable = false, length = 20)
    private String gradeName;

    private Integer basePrice;

    @Column(nullable = false, length = 20)
    private String section;

    @Column(nullable = false, length = 10)
    private String rowLabel;

    @Column(nullable = false)
    private int seatNumber;

    @Column(name = "is_sellable", nullable = false)
    private boolean sellable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    private Long reservationId;

    private LocalDateTime holdExpiresAt;

    @Column(nullable = false)
    private long configVersion;

    private ScheduleSeat(
            Long scheduleId,
            Long venueSeatId,
            Long venueSeatGradeId,
            String gradeName,
            Integer basePrice,
            String section,
            String rowLabel,
            int seatNumber,
            boolean sellable,
            long configVersion) {
        this.scheduleId = scheduleId;
        this.venueSeatId = venueSeatId;
        this.venueSeatGradeId = venueSeatGradeId;
        this.gradeName = gradeName;
        this.basePrice = basePrice;
        this.section = section;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.sellable = sellable;
        this.configVersion = configVersion;
        this.status = SeatStatus.AVAILABLE;
    }

    /** 좌석 준비에서 회차 좌석을 AVAILABLE로 만든다. 판매 대상이면 가격이 있어야 한다. */
    public static ScheduleSeat create(
            Long scheduleId,
            Long venueSeatId,
            Long venueSeatGradeId,
            String gradeName,
            Integer basePrice,
            String section,
            String rowLabel,
            int seatNumber,
            boolean sellable,
            long configVersion) {
        if (basePrice != null && basePrice <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "좌석 가격은 0원보다 커야 합니다.");
        }
        if (sellable && basePrice == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "판매 대상 좌석에는 가격이 있어야 합니다.");
        }
        if (seatNumber <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "좌석 번호는 1 이상이어야 합니다.");
        }
        if (configVersion < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "좌석 구성 버전은 1 이상이어야 합니다.");
        }
        return new ScheduleSeat(
                scheduleId,
                venueSeatId,
                venueSeatGradeId,
                gradeName,
                basePrice,
                section,
                rowLabel,
                seatNumber,
                sellable,
                configVersion);
    }

    /**
     * AVAILABLE이거나 만료된 HOLD를 이 예약의 HOLD로 바꾼다.
     * 만료된 HOLD의 예전 예약이 결제 중인지는 여기서 알 수 없으므로, 부르는 쪽이 BookingFacade로 먼저 확인하고 정리한다.
     */
    public void hold(Long reservationId, LocalDateTime holdExpiresAt, LocalDateTime now) {
        if (!sellable) {
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_SELLABLE);
        }
        boolean selectable = status == SeatStatus.AVAILABLE || (status == SeatStatus.HOLD && isHoldExpiredAt(now));
        if (!selectable) {
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
        }
        this.status = SeatStatus.HOLD;
        this.reservationId = reservationId;
        this.holdExpiresAt = holdExpiresAt;
    }

    /** 이 예약의 HOLD를 SOLD로 바꾼다. 판매된 좌석은 만료되지 않으므로 만료 시각을 비운다. */
    public void sell(Long reservationId) {
        if (status != SeatStatus.HOLD || !isHeldBy(reservationId)) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "이 예약이 선점한 좌석이 아닙니다.");
        }
        this.status = SeatStatus.SOLD;
        this.holdExpiresAt = null;
    }

    /** 이 예약의 HOLD·SOLD를 AVAILABLE로 돌린다. 다른 예약에 넘어간 좌석은 건드리지 않는다. */
    public void release(Long reservationId) {
        if (status == SeatStatus.AVAILABLE || !isHeldBy(reservationId)) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, "이 예약이 가진 좌석이 아닙니다.");
        }
        this.status = SeatStatus.AVAILABLE;
        this.reservationId = null;
        this.holdExpiresAt = null;
    }

    public boolean isHeldBy(Long reservationId) {
        return this.reservationId != null && this.reservationId.equals(reservationId);
    }

    /** HOLD이고 선점 시간이 지났다. 만료 시각 정각부터 만료로 본다(booking의 만료 조건 hold_expires_at <= now와 같다). */
    public boolean isHoldExpiredAt(LocalDateTime now) {
        return status == SeatStatus.HOLD && !holdExpiresAt.isAfter(now);
    }
}
