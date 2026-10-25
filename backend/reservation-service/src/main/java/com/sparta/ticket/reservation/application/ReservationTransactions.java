package com.sparta.ticket.reservation.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.application.dto.ReservationResult;
import com.sparta.ticket.reservation.application.port.SeatPort;
import com.sparta.ticket.reservation.domain.ChangeStamp;
import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.ReservationTransition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원격 호출 앞뒤의 트랜잭션 단위. 유스케이스는 "트랜잭션, 원격 호출, 트랜잭션"으로 나뉘고, 트랜잭션 부분이 여기에 있다.
 * Note: 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 * 그래서 원격 호출을 하는 유스케이스와 트랜잭션 단위를 다른 빈으로 나눈다.
 */
@Component
@RequiredArgsConstructor
public class ReservationTransactions {

    private final ReservationRepository reservationRepository;
    private final ReservationTransitionExecutor transitionExecutor;
    private final SeatPort seatPort;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** 결제 준비 대상. currentOrderId는 기록할 때 조건으로 쓴다. */
    public record CheckoutTarget(
            UUID reservationId, UUID userId, String currentOrderId, LocalDateTime holdExpiresAt, long baseAmount) {}

    /** 결제 승인 시작 결과. started가 false면 같은 승인이 이미 처리됐고 current가 지금 상태다. */
    public record PaymentStart(boolean started, long amount, ReservationResult current) {}

    /** 결과를 확인할 결제 중 예약. since는 결제 중이 된 시각(updated_at)이다. */
    public record PendingPayment(
            UUID reservationId, String orderId, String pgAuthToken, long amount, LocalDateTime since) {}

    @Transactional(readOnly = true)
    public CheckoutTarget loadCheckoutTarget(UUID userId, UUID reservationId) {
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
                reservation.getOrderId(),
                reservation.getHoldExpiresAt(),
                seatPort.priceSnapshot(reservation.getSeatIds()));
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
        if (find(target.reservationId()).isHoldExpiredAt(stamp.at())) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        throw new BusinessException(ReservationErrorCode.CHECKOUT_CONFLICT);
    }

    /**
     * HOLD → PAYMENT_IN_PROGRESS. 0행이면 다시 읽고 이유를 가린다.
     * 같은 orderId의 승인이 이미 진행됐거나 끝났으면 오류 대신 지금 상태를 돌려준다(더블 클릭, 클라이언트 재시도).
     */
    @Transactional
    public PaymentStart startPayment(UUID userId, UUID reservationId, ConfirmCommand command) {
        ChangeStamp stamp = changeStamps.stamp();
        boolean started = transitionExecutor.execute(
                ReservationTransition.START_PAYMENT,
                List.of(),
                () -> reservationRepository.startPayment(
                        reservationId, userId, command.orderId(), command.pgAuthToken(), stamp));
        Reservation reservation = find(reservationId);
        if (started) {
            return new PaymentStart(true, reservation.getPaymentAmount(), ReservationResult.from(reservation));
        }
        validateOwner(reservation, userId);
        if (!reservation.isAttempt(command.orderId())) {
            throw new BusinessException(ReservationErrorCode.ORDER_ID_MISMATCH);
        }
        if (reservation.getStatus() != ReservationStatus.HOLD) {
            return new PaymentStart(false, 0, ReservationResult.from(reservation));
        }
        if (reservation.isHoldExpiredAt(stamp.at())) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }

    /** HOLD → EXPIRED와 좌석 반환. 이미 다른 경로로 바뀌었으면 false. */
    @Transactional
    public boolean expireHold(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return false;
        }
        ChangeStamp stamp = changeStamps.stamp();
        boolean expired = transitionExecutor.execute(
                ReservationTransition.EXPIRE_HOLD,
                reservation.getSeatIds(),
                () -> reservationRepository.expireHold(reservationId, stamp));
        if (expired) {
            metrics.expiredHold();
        }
        return expired;
    }

    /** 아직 결제 중이면 다시 보낼 값을 돌려준다. 그사이 다른 경로로 끝났으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<PendingPayment> readPendingPayment(UUID reservationId) {
        return reservationRepository
                .findById(reservationId)
                .filter(reservation -> reservation.getStatus() == ReservationStatus.PAYMENT_IN_PROGRESS)
                .map(reservation -> new PendingPayment(
                        reservation.getId(),
                        reservation.getOrderId(),
                        reservation.getPgAuthToken(),
                        reservation.getPaymentAmount(),
                        reservation.getUpdatedAt()));
    }

    @Transactional(readOnly = true)
    public ReservationResult read(UUID reservationId) {
        return ReservationResult.from(find(reservationId));
    }

    private Reservation find(UUID reservationId) {
        return reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    private static void validateOwner(Reservation reservation, UUID userId) {
        if (!reservation.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
