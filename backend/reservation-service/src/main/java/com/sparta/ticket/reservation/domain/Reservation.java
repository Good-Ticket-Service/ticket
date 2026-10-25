package com.sparta.ticket.reservation.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 예약. 상태를 바꾸는 setter는 없다. 상태는 {@link ReservationRepository}의 조건부 UPDATE로만 바뀐다.
 * 이 객체는 판단에 필요한 값을 읽는 데만 쓰고, 읽은 뒤 바뀌었을 수 있으므로 전이 성공 여부는 UPDATE 결과로 판단한다.
 */
@Getter
@Entity
@Table(name = "p_reservations")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reservation_id")
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime holdExpiresAt;

    @Column(length = 64)
    private String orderId;

    private Long paymentAmount;

    @Column(length = 255)
    private String pgAuthToken;

    private LocalDateTime paymentCheckedAt;

    @ElementCollection
    @CollectionTable(name = "p_reservation_seats", joinColumns = @JoinColumn(name = "reservation_id"))
    @Column(name = "seat_id", nullable = false)
    private Set<UUID> seatIds = new HashSet<>();

    private Reservation(UUID userId, UUID scheduleId, Set<UUID> seatIds, LocalDateTime holdExpiresAt) {
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.seatIds = seatIds;
        this.holdExpiresAt = holdExpiresAt;
        this.status = ReservationStatus.HOLD;
    }

    /** 좌석 선점과 같은 트랜잭션에서 HOLD 예약을 만든다. */
    public static Reservation hold(
            UUID userId, UUID scheduleId, Collection<UUID> seatIds, LocalDateTime holdExpiresAt) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "선점할 좌석이 없습니다.");
        }
        return new Reservation(userId, scheduleId, new HashSet<>(seatIds), holdExpiresAt);
    }

    /** 좌석 id를 오름차순으로 돌려준다. 좌석은 이 순서로 잠근다. */
    public List<UUID> getSeatIds() {
        return seatIds.stream().sorted().toList();
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public boolean isHoldExpiredAt(LocalDateTime now) {
        return !holdExpiresAt.isAfter(now);
    }

    public boolean isAttempt(String orderId) {
        return orderId != null && orderId.equals(this.orderId);
    }
}
