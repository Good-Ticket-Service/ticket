package com.sparta.ticket.reservation.booking.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private LocalDateTime scheduleStartAt;

    @Column(nullable = false, length = 200)
    private String performanceTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime holdExpiresAt;

    @Column(length = 64)
    private String orderId;

    private Long paymentAmount;

    private Long userCouponId;

    private Long discountAmount;

    @Column(length = 255)
    private String pgAuthToken;

    private LocalDateTime paymentCheckedAt;

    private LocalDateTime refundRequiredAt;

    private LocalDateTime cancelCheckedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.PERSIST)
    private List<Ticket> tickets = new ArrayList<>();

    private Reservation(Long userId, SaleTarget target, LocalDateTime holdExpiresAt) {
        this.userId = userId;
        this.scheduleId = target.scheduleId();
        this.performanceId = target.performanceId();
        this.sellerId = target.sellerId();
        this.scheduleStartAt = target.scheduleStartAt();
        this.performanceTitle = target.performanceTitle();
        this.holdExpiresAt = holdExpiresAt;
        this.status = ReservationStatus.HOLD;
    }

    /** 좌석 선점과 같은 트랜잭션에서 HOLD 예약과 좌석마다 티켓을 만든다. */
    public static Reservation hold(Long userId, SaleTarget target, List<SeatPrice> seats, LocalDateTime holdExpiresAt) {
        if (!target.isComplete()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "회차·공연·판매자·회차 시작 시각·공연명은 모두 있어야 합니다.");
        }
        if (seats == null || seats.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "선점할 좌석이 없습니다.");
        }
        if (seats.stream().map(SeatPrice::scheduleSeatId).distinct().count() != seats.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "같은 좌석을 두 번 선점할 수 없습니다.");
        }
        if (seats.stream().anyMatch(seat -> seat.price() < 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "좌석 가격은 0원 이상이어야 합니다.");
        }
        Reservation reservation = new Reservation(userId, target, holdExpiresAt);
        seats.forEach(seat -> reservation.tickets.add(new Ticket(reservation, seat)));
        return reservation;
    }

    /** 좌석 id를 오름차순으로 돌려준다. 좌석은 이 순서로 잠근다. */
    public List<Long> getSeatIds() {
        return tickets.stream().map(Ticket::getScheduleSeatId).sorted().toList();
    }

    /** 티켓을 좌석 id 오름차순으로 돌려준다. 응답마다 순서가 같도록 정렬한다. */
    public List<Ticket> ticketsBySeat() {
        return tickets.stream()
                .sorted(Comparator.comparing(Ticket::getScheduleSeatId))
                .toList();
    }

    /** 티켓 가격의 합계. 할인 전 결제 기본 금액이다. */
    public long baseAmount() {
        return tickets.stream().mapToLong(Ticket::getPrice).sum();
    }

    /** 확정 전이가 성공한 트랜잭션에서만 부른다. 티켓마다 코드를 발급한다. */
    public void issueTicketCodes() {
        tickets.forEach(Ticket::issueCode);
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isHoldExpiredAt(LocalDateTime now) {
        return !holdExpiresAt.isAfter(now);
    }

    public boolean isAttempt(String orderId) {
        return orderId != null && orderId.equals(this.orderId);
    }

    // Note: 아래 판단은 다시 읽은 예약으로 "왜 전이가 0행이었는지"를 가릴 때만 쓴다.
    // 동시 요청을 막는 것은 ReservationRepository 조건부 UPDATE의 WHERE이고, 같은 조건이 거기에도 있다.

    /** 이 orderId의 결제 시도가 지금 진행 중이다. 결과를 반영해도 되는 시도인지 가를 때 쓴다. */
    public boolean isCurrentPayment(String orderId) {
        return status == ReservationStatus.PAYMENT_IN_PROGRESS && isAttempt(orderId);
    }

    /** 이 orderId의 결제 시도로 확정된 적이 있다. 같은 성공이 다시 온 것이다. 확정 뒤 취소 중이거나 취소됐어도 같다. */
    public boolean wasConfirmedBy(String orderId) {
        return (status == ReservationStatus.CONFIRMED
                        || status == ReservationStatus.CANCELING
                        || status == ReservationStatus.CANCELED)
                && isAttempt(orderId);
    }

    /** 사용자 해제나 시간 초과로 이미 끝난 선점이다. */
    public boolean isClosedHold() {
        return status == ReservationStatus.RELEASED || status == ReservationStatus.EXPIRED;
    }

    /** 아직 선점 중인데 만료 시각이 지났다. 만료 스케줄러가 좌석을 잠그기 전에 거른다. */
    public boolean isExpirableAt(LocalDateTime now) {
        return status == ReservationStatus.HOLD && isHoldExpiredAt(now);
    }
}
