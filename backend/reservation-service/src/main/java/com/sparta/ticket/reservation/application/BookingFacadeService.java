package com.sparta.ticket.reservation.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.domain.ChangeStamp;
import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.SeatPrice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
    public Long hold(UUID userId, Long scheduleId, List<SeatPrice> seats, LocalDateTime holdExpiresAt) {
        return reservationRepository
                .save(Reservation.hold(userId, scheduleId, seats, holdExpiresAt))
                .getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID userId, Long reservationId) {
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
