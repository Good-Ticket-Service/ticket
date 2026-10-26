package com.sparta.ticket.reservation.application;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.application.dto.ReservationResult;
import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;

    /** 예약 상태 조회. 결제 승인 뒤 PAYMENT_IN_PROGRESS를 받은 클라이언트가 폴링한다. 본인과 MASTER만 볼 수 있다. */
    public ReservationResult get(UserContext user, Long reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        user.validateSelfOrMaster(reservation.getUserId());
        return ReservationResult.from(reservation);
    }
}
