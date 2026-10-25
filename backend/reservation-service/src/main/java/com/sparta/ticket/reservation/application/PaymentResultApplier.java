package com.sparta.ticket.reservation.application;

import com.sparta.ticket.reservation.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.domain.ChangeStamp;
import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.ReservationTransition;
import java.util.UUID;
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
    public boolean apply(UUID reservationId, String orderId, ConfirmOutcome outcome) {
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

    private boolean applySuccess(Reservation reservation, String orderId, ChangeStamp stamp) {
        UUID id = reservation.getId();
        if (transitionExecutor.execute(
                ReservationTransition.CONFIRM,
                reservation.getSeatIds(),
                () -> reservationRepository.confirm(id, orderId, stamp))) {
            return true;
        }
        Reservation current = reservationRepository.findById(id).orElseThrow();
        if (current.isAttempt(orderId) && current.getStatus() == ReservationStatus.CONFIRMED) {
            return false;
        }
        metrics.lateSuccess();
        log.warn(
                "결제는 됐는데 표가 없다. 자동 환불 대상(F6)이다. reservationId={}, orderId={}, status={}, currentOrderId={}",
                id,
                orderId,
                current.getStatus(),
                current.getOrderId());
        return false;
    }

    /** 같은 실패가 다시 왔거나 지난 시도의 실패면 조건부 UPDATE가 0행이라 아무것도 바뀌지 않는다. */
    private boolean applyFailure(Reservation reservation, String orderId, ChangeStamp stamp) {
        UUID id = reservation.getId();
        if (reservation.isHoldExpiredAt(stamp.at())) {
            return transitionExecutor.execute(
                    ReservationTransition.EXPIRE_AFTER_FAILURE,
                    reservation.getSeatIds(),
                    () -> reservationRepository.expireAfterFailure(id, orderId, stamp));
        }
        return transitionExecutor.execute(
                ReservationTransition.RETURN_TO_HOLD,
                reservation.getSeatIds(),
                () -> reservationRepository.returnToHold(id, orderId, stamp));
    }
}
