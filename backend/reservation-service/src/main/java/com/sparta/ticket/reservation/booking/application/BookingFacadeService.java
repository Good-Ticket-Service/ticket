package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.ReleaseResult;
import com.sparta.ticket.reservation.booking.application.facade.ReservationState;
import com.sparta.ticket.reservation.booking.application.facade.ReservationView;
import com.sparta.ticket.reservation.booking.application.facade.SeatLine;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.SeatPrice;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookingFacadeService implements BookingFacade {

    private final ReservationRepository reservationRepository;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** 좌석과 예약이 한 트랜잭션에서 바뀌어야 하므로, 바깥 트랜잭션 없이 부르면 예외로 막는다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long hold(Long userId, Long scheduleId, List<SeatLine> seats, LocalDateTime holdExpiresAt) {
        List<SeatPrice> prices = seats.stream()
                .map(seat -> new SeatPrice(seat.scheduleSeatId(), seat.price()))
                .toList();
        // Note: save만 하면 INSERT가 커밋 때로 미뤄질 수 있다. 바로 flush해서 UNIQUE 위반을 이 호출 안에서 드러낸다.
        // 그러면 seat는 좌석을 바꾸기 전에 실패를 알고, 트랜잭션 밖에서 제약 이름으로 409를 만든다(B31).
        return reservationRepository
                .saveAndFlush(Reservation.hold(userId, scheduleId, prices, holdExpiresAt))
                .getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReservationView> getReservation(Long reservationId) {
        return reservationRepository.findById(reservationId).map(BookingFacadeService::toView);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReleaseResult release(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        ChangeStamp stamp = changeStamps.stamp();
        if (reservationRepository.releaseHold(reservationId, userId, stamp) == 1) {
            metrics.releasedHold();
            return ReleaseResult.RELEASED;
        }
        // Note: 시간이 지났는데 만료 스케줄러가 아직 돌지 않은 선점이다. 사용자에게는 어느 쪽이든 좌석이 풀린 것이므로
        // 오류 대신 EXPIRED로 정리한다. 시간 초과와 사용자 해제를 따로 세려고 RELEASED로 바꾸지 않는다.
        if (reservationRepository.expireHold(reservationId, stamp) == 1) {
            metrics.expiredHold();
            return ReleaseResult.EXPIRED;
        }
        ReservationStatus status =
                reservationRepository.reload(reservationId).orElseThrow().getStatus();
        if (status == ReservationStatus.RELEASED || status == ReservationStatus.EXPIRED) {
            return ReleaseResult.ALREADY_CLOSED;
        }
        // Note: 결제 중·확정·취소는 예외 대신 결과로 알린다. seat가 204와 409를 고르는 근거다(#34).
        return ReleaseResult.NOT_RELEASABLE;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int countActiveSeats(Long userId, Long scheduleId) {
        throw new UnsupportedOperationException("구현 전 (#59)");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReservationView> findActiveHold(Long userId, Long scheduleId) {
        throw new UnsupportedOperationException("구현 전 (#59)");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfDue(Long reservationId) {
        throw new UnsupportedOperationException("구현 전 (#59)");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Long> findPaymentInProgress(Collection<Long> reservationIds) {
        throw new UnsupportedOperationException("구현 전 (#59)");
    }

    private static ReservationView toView(Reservation reservation) {
        return new ReservationView(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getScheduleId(),
                ReservationState.valueOf(reservation.getStatus().name()),
                reservation.getHoldExpiresAt(),
                reservation.getSeatIds());
    }
}
