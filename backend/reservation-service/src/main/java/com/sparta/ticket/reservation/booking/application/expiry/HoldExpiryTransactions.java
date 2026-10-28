package com.sparta.ticket.reservation.booking.application.expiry;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 스케줄러가 예약 한 건마다 여는 트랜잭션.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * 건마다 따로 커밋해야 한 건의 실패가 나머지를 되돌리지 않으므로, 후보를 도는 HoldExpiryService와 다른 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class HoldExpiryTransactions {

    private final ReservationRepository reservationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** HOLD → EXPIRED와 좌석 반환. 이미 다른 경로로 바뀌었으면 좌석을 잠그지 않고 false. */
    @Transactional
    public boolean expireHold(Long reservationId) {
        ChangeStamp stamp = changeStamps.stamp();
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null
                || reservation.getStatus() != ReservationStatus.HOLD
                || !reservation.isHoldExpiredAt(stamp.at())) {
            return false;
        }
        boolean expired = transitionExecutor.execute(
                ReservationTransition.EXPIRE_HOLD,
                reservationId,
                reservation.getSeatIds(),
                () -> reservationRepository.expireHold(reservationId, stamp));
        if (expired) {
            metrics.expiredHold();
        }
        return expired;
    }
}
