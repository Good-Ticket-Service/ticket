package com.sparta.ticket.reservation.booking.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 예약이 담은 좌석 한 장. 선점할 때 예약과 함께 만들고, 좌석과 가격은 바꾸지 않는다.
 * 좌석의 지금 상태와 지금 잡고 있는 예약은 seat 모듈의 schedule_seat이 가진다. 티켓이 유효한지는 예약 상태로 판단한다.
 */
@Getter
@Entity
@Table(name = "p_tickets")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(nullable = false)
    private Long scheduleSeatId;

    @Column(nullable = false)
    private int price;

    @Column(length = 36)
    private String ticketCode;

    Ticket(Reservation reservation, SeatPrice seat) {
        this.reservation = reservation;
        this.scheduleSeatId = seat.scheduleSeatId();
        this.price = seat.price();
    }

    /** 예약이 확정될 때 한 번만 발급한다. 이미 있으면 그대로 둔다. */
    void issueCode() {
        if (ticketCode == null) {
            ticketCode = UUID.randomUUID().toString();
        }
    }
}
