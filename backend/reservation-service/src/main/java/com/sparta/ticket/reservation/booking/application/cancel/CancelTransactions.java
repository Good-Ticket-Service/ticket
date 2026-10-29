package com.sparta.ticket.reservation.booking.application.cancel;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.ReservationTransitionExecutor;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 취소에서 환불 요청 앞뒤의 트랜잭션 단위. 취소는 "취소 시작, 환불 요청, 취소 확정"으로 나뉘고 트랜잭션 부분이 여기에 있다.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * 환불 요청은 트랜잭션 밖에서 해야 하므로 원격 호출을 하는 ReservationCancelService와 다른 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class CancelTransactions {

    private final ReservationRepository reservationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** 취소 시작 결과. started가 false면 이미 취소 중이거나 취소됐고 current가 지금 상태다. */
    public record CancelStart(boolean started, String orderId, ReservationResult current) {}

    /**
     * CONFIRMED → CANCELING. 환불을 요청하기 전에 커밋해야, 환불 결과를 모를 때도 취소 확인 스케줄러가 이 예약을 찾아 마무리한다.
     * 0행이면 다시 읽고 이유를 가린다. 이미 취소 중이거나 취소됐으면 오류 대신 지금 상태를 돌려준다(더블 클릭).
     */
    @Transactional
    public CancelStart startCancel(Long userId, Long reservationId) {
        ChangeStamp stamp = changeStamps.stamp();
        boolean started = reservationRepository.startCancel(reservationId, userId, stamp) == 1;
        Reservation reservation = reload(reservationId);
        if (started) {
            return new CancelStart(true, reservation.getOrderId(), ReservationResult.from(reservation));
        }
        if (!reservation.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return switch (reservation.getStatus()) {
            case CANCELING, CANCELED -> new CancelStart(false, null, ReservationResult.from(reservation));
            // 본인 예약이고 확정 상태인데 바꾸지 못했으면 남은 조건은 기한뿐이다
            case CONFIRMED -> throw new BusinessException(ReservationErrorCode.NOT_CANCELLABLE);
            case HOLD, PAYMENT_IN_PROGRESS, EXPIRED, RELEASED ->
                throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        };
    }

    /**
     * payment가 환불 요청을 받았으니 CANCELING → CANCELED로 바꾸고 좌석을 반환한다. 좌석을 먼저 잠근다(잠금 순서: 좌석 → 예약).
     * 회차가 시작됐어도 끝낸다. 기한은 취소 시작에서만 본다. 그사이 다른 경로가 먼저 끝냈으면 좌석을 건드리지 않고 false다.
     */
    @Transactional
    public boolean finishCancel(Long reservationId) {
        ChangeStamp stamp = changeStamps.stamp();
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.CANCELING) {
            return false;
        }
        boolean canceled = transitionExecutor.cancelAndRelease(
                reservationId, reservation.getSeatIds(), () -> reservationRepository.cancel(reservationId, stamp));
        if (canceled) {
            metrics.canceled();
        }
        return canceled;
    }

    /**
     * 아직 취소 중이고 checkedBefore 이전에 확인한 예약이면 확인했다고 남기고(다음 차례에서 뒤로 간다) 환불을 다시 요청할 orderId를
     * 돌려준다. 남기지 못했으면 다른 인스턴스가 먼저 골랐거나 그사이 끝난 것이라 비어 있다.
     * 환불 요청 전에 커밋해야, 요청이 실패해도 같은 예약을 계속 앞에서 고르지 않는다.
     */
    @Transactional
    public Optional<String> claimPendingCancel(Long reservationId, LocalDateTime checkedBefore) {
        return reservationRepository
                .findById(reservationId)
                .filter(reservation -> reservation.getStatus() == ReservationStatus.CANCELING)
                .filter(reservation ->
                        reservationRepository.markCancelChecked(reservationId, changeStamps.now(), checkedBefore) == 1)
                .map(Reservation::getOrderId);
    }

    @Transactional(readOnly = true)
    public ReservationResult read(Long reservationId) {
        return ReservationResult.from(reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND)));
    }

    private Reservation reload(Long reservationId) {
        return reservationRepository
                .reload(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }
}
