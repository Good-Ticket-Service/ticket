package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 준비·승인 요청에서 원격 호출 앞뒤의 트랜잭션 단위. 유스케이스는 "트랜잭션, 원격 호출, 트랜잭션"으로 나뉘고,
 * 트랜잭션 부분이 여기에 있다.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * 그래서 원격 호출을 하는 유스케이스(ReservationPaymentService)와 트랜잭션 단위를 다른 빈으로 나눈다.
 */
@Component
@RequiredArgsConstructor
public class PaymentRequestTransactions {

    private final ReservationRepository reservationRepository;
    private final ChangeStamps changeStamps;

    /** 결제 준비 대상. currentOrderId는 기록할 때 조건으로 쓴다. */
    public record CheckoutTarget(
            Long reservationId,
            Long userId,
            Long scheduleId,
            Long performanceId,
            Long sellerId,
            String currentOrderId,
            LocalDateTime holdExpiresAt,
            long baseAmount) {}

    /** 결제 승인 시작 결과. started가 false면 같은 승인이 이미 처리됐고 current가 지금 상태다. */
    public record PaymentStart(boolean started, long amount, ReservationResult current) {}

    @Transactional(readOnly = true)
    public CheckoutTarget loadCheckoutTarget(Long userId, Long reservationId) {
        Reservation reservation = find(reservationId);
        validateOwner(reservation, userId);
        if (reservation.getStatus() != ReservationStatus.HOLD) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        }
        if (reservation.isHoldExpiredAt(changeStamps.now())) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        return new CheckoutTarget(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getScheduleId(),
                reservation.getPerformanceId(),
                reservation.getSellerId(),
                reservation.getOrderId(),
                reservation.getHoldExpiresAt(),
                reservation.baseAmount());
    }

    /** 준비 결과를 기록한다. 그사이 다른 준비나 승인이 먼저 반영됐으면 0행이라 실패한다. */
    @Transactional
    public void recordCheckout(CheckoutTarget target, String orderId, long paymentAmount) {
        ChangeStamp stamp = changeStamps.stamp();
        int updated = reservationRepository.recordCheckout(
                target.reservationId(), target.userId(), target.currentOrderId(), orderId, paymentAmount, stamp);
        if (updated == 1) {
            return;
        }
        if (reload(target.reservationId()).isHoldExpiredAt(stamp.at())) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        throw new BusinessException(ReservationErrorCode.CHECKOUT_CONFLICT);
    }

    /**
     * HOLD → PAYMENT_IN_PROGRESS. 0행이면 다시 읽고 이유를 가린다.
     * 같은 orderId의 승인이 이미 진행 중이거나 확정됐으면 오류 대신 지금 상태를 돌려준다(더블 클릭, 클라이언트 재시도).
     * 만료는 만료 스케줄러가 돌았는지와 관계없이 HOLD_EXPIRED로 답한다.
     */
    @Transactional
    public PaymentStart startPayment(Long userId, Long reservationId, ConfirmCommand command) {
        ChangeStamp stamp = changeStamps.stamp();
        boolean started = reservationRepository.startPayment(
                        reservationId, userId, command.orderId(), command.pgAuthToken(), stamp)
                == 1;
        Reservation reservation = reload(reservationId);
        if (started) {
            return new PaymentStart(true, reservation.getPaymentAmount(), ReservationResult.from(reservation));
        }
        return notStarted(reservation, userId, command, stamp);
    }

    /** 전이가 0행이었을 때, 다시 읽은 예약으로 이유를 가린다. 본인 예약인지, 같은 시도의 orderId인지를 상태보다 먼저 본다. */
    private PaymentStart notStarted(Reservation reservation, Long userId, ConfirmCommand command, ChangeStamp stamp) {
        validateOwner(reservation, userId);
        if (!reservation.isAttempt(command.orderId())) {
            throw new BusinessException(ReservationErrorCode.ORDER_ID_MISMATCH);
        }
        return switch (reservation.getStatus()) {
            case PAYMENT_IN_PROGRESS, CONFIRMED -> new PaymentStart(false, 0, ReservationResult.from(reservation));
            case EXPIRED -> throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
            case HOLD ->
                throw new BusinessException(
                        reservation.isHoldExpiredAt(stamp.at())
                                ? ReservationErrorCode.HOLD_EXPIRED
                                : CommonErrorCode.INVALID_STATUS_TRANSITION);
            case RELEASED, CANCELED -> throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        };
    }

    @Transactional(readOnly = true)
    public ReservationResult read(Long reservationId) {
        return ReservationResult.from(find(reservationId));
    }

    private Reservation find(Long reservationId) {
        return reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    private Reservation reload(Long reservationId) {
        return reservationRepository
                .reload(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    private static void validateOwner(Reservation reservation, Long userId) {
        if (!reservation.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
