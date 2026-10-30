package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.DiscountCap;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.LocalDateTime;
import java.util.Objects;
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
    private final CouponPort couponPort;

    /** 결제 준비 대상. currentOrderId는 기록할 때, userCouponId는 쿠폰을 바꿀 때 조건으로 쓴다. */
    public record CheckoutTarget(
            Long reservationId,
            Long userId,
            Long scheduleId,
            Long performanceId,
            Long sellerId,
            String currentOrderId,
            LocalDateTime holdExpiresAt,
            LocalDateTime scheduleStartAt,
            long baseAmount,
            Long userCouponId,
            long discountAmount) {

        /** 쿠폰을 바꾼 뒤의 대상. 쿠폰을 바꾸면 지난 준비의 orderId를 지우므로 기록 조건도 null이 된다. */
        CheckoutTarget withCoupon(Long newUserCouponId, long newDiscountAmount) {
            return new CheckoutTarget(
                    reservationId,
                    userId,
                    scheduleId,
                    performanceId,
                    sellerId,
                    null,
                    holdExpiresAt,
                    scheduleStartAt,
                    baseAmount,
                    newUserCouponId,
                    newDiscountAmount);
        }
    }

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
                reservation.getScheduleStartAt(),
                reservation.baseAmount(),
                reservation.getUserCouponId(),
                reservation.getDiscountAmount() == null ? 0 : reservation.getDiscountAmount());
    }

    /**
     * 요청한 쿠폰을 예약에 반영하고, 결제 준비에 넘길 대상을 돌려준다. 쿠폰이 지난 준비와 같으면 다시 잡지 않는다.
     * 잡힌 뒤에는 유효 기간이 지나도 쓸 수 있으므로 다시 확인할 이유가 없다. 쿠폰을 보내지 않으면 잡은 쿠폰을 뺀다.
     * 쿠폰을 쓸 수 없으면 COUPON_NOT_AVAILABLE이고, 트랜잭션 전체가 롤백돼 예약과 쿠폰 모두 그대로다.
     */
    @Transactional
    public CheckoutTarget applyCoupon(CheckoutTarget target, Long userCouponId) {
        if (Objects.equals(target.userCouponId(), userCouponId)) {
            return target;
        }
        // Note: 예약 행을 쿠폰보다 먼저 바꾼다. 이 UPDATE가 행을 잡아 다른 탭의 준비는 0행으로 CHECKOUT_CONFLICT가 된다.
        // 쿠폰부터 잡으면 두 준비가 예약 하나에 쿠폰 하나라는 쿠폰 쪽 UK에 부딪혀 예외로 끝난다.
        ChangeStamp stamp = changeStamps.stamp();
        Long reservationId = target.reservationId();
        if (reservationRepository.changeCoupon(
                        reservationId, target.userId(), target.userCouponId(), userCouponId, stamp)
                == 0) {
            throw checkoutConflict(reservationId, stamp);
        }
        if (target.userCouponId() != null) {
            couponPort.release(reservationId);
        }
        if (userCouponId == null) {
            return target.withCoupon(null, 0);
        }
        long couponAmount = couponPort.reserve(userCouponId, target.userId(), reservationId);
        long discountAmount = DiscountCap.of(target.baseAmount(), couponAmount);
        reservationRepository.recordDiscount(reservationId, discountAmount);
        return target.withCoupon(userCouponId, discountAmount);
    }

    /** 준비 결과를 기록한다. 그사이 다른 준비나 승인이 먼저 반영됐거나 쿠폰이 바뀌었으면 0행이라 실패한다. */
    @Transactional
    public void recordCheckout(CheckoutTarget target, String orderId, long paymentAmount) {
        ChangeStamp stamp = changeStamps.stamp();
        int updated = reservationRepository.recordCheckout(
                target.reservationId(),
                target.userId(),
                target.currentOrderId(),
                target.userCouponId(),
                orderId,
                paymentAmount,
                stamp);
        if (updated == 1) {
            return;
        }
        throw checkoutConflict(target.reservationId(), stamp);
    }

    /** 준비를 반영하는 UPDATE가 0행이었을 때의 오류. 그사이 만료됐는지, 다른 준비나 승인이 먼저 반영됐는지 가린다. */
    private BusinessException checkoutConflict(Long reservationId, ChangeStamp stamp) {
        if (reload(reservationId).isHoldExpiredAt(stamp.at())) {
            return new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        return new BusinessException(ReservationErrorCode.CHECKOUT_CONFLICT);
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
            case RELEASED, CANCELING, CANCELED ->
                throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
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
