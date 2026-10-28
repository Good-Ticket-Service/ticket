package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 결과를 예약에 반영하는 유일한 곳. Phase 1은 승인 응답이, Phase 2는 Kafka 리스너가, 결과 확인 스케줄러는 조회 결과가
 * 같은 이 코드를 부른다. 그래야 두 Phase의 측정 차이를 통신 방식 때문이라고 말할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResultApplier {

    private final ReservationRepository reservationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** 예약 상태가 바뀌었으면 true. 결과를 모르거나, 같은 결과가 다시 왔거나, 지난 시도의 결과면 false. */
    @Transactional
    public boolean apply(Long reservationId, String orderId, ConfirmOutcome outcome) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.warn("결제 결과의 예약이 없다. reservationId={}, orderId={}", reservationId, orderId);
            return false;
        }
        ChangeStamp stamp = changeStamps.stamp();
        return switch (outcome.result()) {
            case SUCCEEDED -> applySuccess(reservation, orderId, stamp);
            case FAILED -> applyFailure(reservation, orderId, stamp);
            case UNKNOWN -> false;
        };
    }

    /** 지금 시도의 결제 중 예약일 때만 좌석을 잠그고 확정한다. 그 밖의 성공은 좌석을 건드리지 않고 가린다. */
    private boolean applySuccess(Reservation reservation, String orderId, ChangeStamp stamp) {
        Long id = reservation.getId();
        Reservation current = reservation;
        if (isCurrentPayment(reservation, orderId)) {
            if (transitionExecutor.execute(
                    ReservationTransition.CONFIRM,
                    id,
                    reservation.getSeatIds(),
                    () -> reservationRepository.confirm(id, orderId, stamp))) {
                reservation.issueTicketCodes();
                return true;
            }
            current = reservationRepository.reload(id).orElseThrow();
        }
        if (current.isAttempt(orderId) && current.getStatus() == ReservationStatus.CONFIRMED) {
            return false;
        }
        // Note: 확정하지 못한 지금 시도는 결제 중으로 남아 결과 확인이 같은 성공을 계속 다시 받는다.
        // 처음 한 번만 환불 대상으로 세야 환불할 결제 수가 부풀지 않는다.
        if (isCurrentPayment(current, orderId)
                && reservationRepository.markRefundRequired(id, orderId, stamp.at()) == 0) {
            return false;
        }
        metrics.lateSuccess();
        log.warn(
                "결제는 됐는데 표가 없다. 자동 환불 대상이다. reservationId={}, orderId={}, status={}, currentOrderId={}",
                id,
                orderId,
                current.getStatus(),
                current.getOrderId());
        return false;
    }

    /** 같은 실패가 다시 왔거나 지난 시도의 실패면 아무것도 바꾸지 않는다. */
    private boolean applyFailure(Reservation reservation, String orderId, ChangeStamp stamp) {
        if (!isCurrentPayment(reservation, orderId)) {
            return false;
        }
        Long id = reservation.getId();
        if (reservation.isHoldExpiredAt(stamp.at())) {
            return transitionExecutor.execute(
                    ReservationTransition.EXPIRE_AFTER_FAILURE,
                    id,
                    reservation.getSeatIds(),
                    () -> reservationRepository.expireAfterFailure(id, orderId, stamp));
        }
        return transitionExecutor.execute(
                ReservationTransition.RETURN_TO_HOLD,
                id,
                reservation.getSeatIds(),
                () -> reservationRepository.returnToHold(id, orderId, stamp));
    }

    private static boolean isCurrentPayment(Reservation reservation, String orderId) {
        return reservation.getStatus() == ReservationStatus.PAYMENT_IN_PROGRESS && reservation.isAttempt(orderId);
    }
}
