package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.SeatPrice;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 좌석과 예약이 한 트랜잭션에서 바뀌어야 하므로, 바깥 트랜잭션 없이 부르면 예외로 막는다.
     * 같은 회원이 같은 회차에 진행 중인 예약을 또 만들면 부분 UNIQUE(uk_p_reservations_active_user_schedule)
     * 위반 예외를 잡지 않고 그대로 던진다. seat가 그 제약 이름을 보고 409 PURCHASE_LIMIT_EXCEEDED로 바꾼다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long hold(Long userId, Long scheduleId, List<SeatLine> seats, LocalDateTime holdExpiresAt) {
        List<SeatPrice> prices = seats.stream()
                .map(seat -> new SeatPrice(seat.scheduleSeatId(), seat.price()))
                .toList();
        // Note: save만 하면 INSERT가 커밋 때로 미뤄질 수 있다. 그러면 UNIQUE 위반이 seat의 호출 안이 아니라
        // 커밋 시점에 터져 seat가 예외를 잡아 409로 바꿀 수 없다. 그래서 여기서 바로 flush한다.
        return reservationRepository
                .saveAndFlush(Reservation.hold(userId, scheduleId, prices, holdExpiresAt))
                .getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        ChangeStamp stamp = changeStamps.stamp();
        if (reservationRepository.releaseHold(reservationId, userId, stamp) == 1) {
            metrics.releasedHold();
            return;
        }
        // Note: 시간이 지났는데 만료 스케줄러가 아직 돌지 않은 선점이다. 사용자에게는 어느 쪽이든 좌석이 풀린 것이므로
        // 오류 대신 EXPIRED로 정리한다. 시간 초과와 사용자 해제를 따로 세려고 RELEASED로 바꾸지 않는다.
        if (reservationRepository.expireHold(reservationId, stamp) == 1) {
            metrics.expiredHold();
            return;
        }
        ReservationStatus status =
                reservationRepository.reload(reservationId).orElseThrow().getStatus();
        if (status != ReservationStatus.RELEASED && status != ReservationStatus.EXPIRED) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_STATUS_TRANSITION, "결제가 진행 중이거나 끝난 예약은 선점을 해제할 수 없습니다.");
        }
    }
}
