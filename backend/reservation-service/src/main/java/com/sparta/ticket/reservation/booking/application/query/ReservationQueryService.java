package com.sparta.ticket.reservation.booking.application.query;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.page.SortAllowList;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationSummary;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationQueryService {

    /** 내 예매 목록에서 정렬할 수 있는 필드. */
    static final Set<String> MY_RESERVATION_SORTS = Set.of("createdAt");

    /** 상태를 고르지 않았을 때 보여 줄 예약. 결제 없이 끝난 만료·해제 예약은 쌓이기만 하고 회원이 할 일이 없어 뺀다. */
    private static final Set<ReservationStatus> DEFAULT_STATUSES =
            EnumSet.complementOf(EnumSet.of(ReservationStatus.EXPIRED, ReservationStatus.RELEASED));

    private final ReservationRepository reservationRepository;

    /** 예약 상태 조회. 결제 승인 뒤 PAYMENT_IN_PROGRESS를 받은 클라이언트가 폴링한다. 본인과 MASTER만 볼 수 있다. */
    public ReservationResult get(UserContext user, Long reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        user.validateSelfOrMaster(reservation.getUserId());
        return ReservationResult.from(reservation);
    }

    /** 내 예매 목록. 페이지·크기 보정과 기본 정렬은 공통 리졸버가 하고, 조회 대상은 요청 값이 아니라 인증한 회원으로 정한다. */
    public Page<ReservationSummary> findMine(Long userId, ReservationStatus status, Pageable pageable) {
        SortAllowList.check(pageable, MY_RESERVATION_SORTS);
        return reservationRepository.findMine(userId, status == null ? DEFAULT_STATUSES : Set.of(status), pageable);
    }
}
