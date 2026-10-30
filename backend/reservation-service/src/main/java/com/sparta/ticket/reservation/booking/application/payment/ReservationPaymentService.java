package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutResult;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions.CheckoutTarget;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions.PaymentStart;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentPort;
import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.booking.application.port.PreparedPayment;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 결제 준비와 승인 유스케이스. 일부러 @Transactional을 붙이지 않는다. payment 호출은 트랜잭션 밖에서 하고,
 * 앞뒤의 DB 변경은 PaymentRequestTransactions와 PaymentResultApplier가 각자의 트랜잭션으로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPaymentService {

    private final PaymentRequestTransactions transactions;
    private final PaymentResultApplier resultApplier;
    private final PaymentPort paymentPort;

    /**
     * 결제를 준비한다. 상태는 HOLD 그대로이고, 고른 쿠폰을 예약에 잡은 뒤 새 orderId와 결제 금액을 기록한다.
     * 결제 호출이 실패하거나 결과를 몰라도 잡은 쿠폰은 그대로 둔다. 같은 쿠폰으로 다시 준비하면 된다.
     */
    public CheckoutResult checkout(UserContext user, Long reservationId, CheckoutCommand command) {
        CheckoutTarget target =
                applyCoupon(transactions.loadCheckoutTarget(user.userId(), reservationId), command.userCouponId());
        String orderId = UUID.randomUUID().toString();
        PreparedPayment prepared = paymentPort.prepare(new PaymentPrepareRequest(
                orderId,
                target.reservationId(),
                target.userId(),
                target.sellerId(),
                target.performanceId(),
                target.scheduleId(),
                target.scheduleStartAt(),
                target.baseAmount(),
                target.discountAmount(),
                command.pgProvider(),
                target.holdExpiresAt()));
        transactions.recordCheckout(target, orderId, prepared.amount());
        return new CheckoutResult(orderId, prepared.amount(), prepared.checkoutUrl());
    }

    /**
     * 쿠폰을 예약에 반영한다. 같은 회원의 두 예약이 서로의 쿠폰으로 동시에 바꾸면 쿠폰 행을 서로 반대 순서로 잠가 DB가 한쪽을 중단할 수 있다.
     * 한쪽은 어차피 상대 쿠폰을 잡지 못하므로, 잠금 실패는 서버 오류가 아니라 다른 준비가 먼저 처리된 것으로 답한다.
     * 트랜잭션이 롤백된 뒤라 예약과 쿠폰은 그대로다. 그 밖의 DB 오류는 바꾸지 않는다.
     */
    private CheckoutTarget applyCoupon(CheckoutTarget target, Long userCouponId) {
        try {
            return transactions.applyCoupon(target, userCouponId);
        } catch (PessimisticLockingFailureException e) {
            log.warn("쿠폰 반영 중 잠금 실패. reservationId={}, userCouponId={}", target.reservationId(), userCouponId, e);
            throw new BusinessException(ReservationErrorCode.CHECKOUT_CONFLICT);
        }
    }

    /**
     * 결제를 승인한다. 결과를 모르면 PAYMENT_IN_PROGRESS로 두고 응답하며, 결과 확인 스케줄러가 마무리한다.
     * 사용자 요청 경로에서는 승인을 다시 보내지 않는다. 기다리는 동안 요청 스레드를 붙잡지 않기 위해서다.
     */
    public ReservationResult confirm(UserContext user, Long reservationId, ConfirmCommand command) {
        PaymentStart start = transactions.startPayment(user.userId(), reservationId, command);
        if (!start.started()) {
            return start.current();
        }
        ConfirmOutcome outcome = requestConfirm(command, start.amount());
        try {
            resultApplier.apply(reservationId, command.orderId(), outcome);
        } catch (RuntimeException e) {
            // Note: 승인은 성공했을 수 있는데 반영만 실패했다. 오류로 답하면 클라이언트는 결제 실패로 안다.
            // 예약은 결제 중 그대로이므로 그 상태를 돌려주고, 결과 확인 스케줄러가 조회해 다시 반영한다.
            log.error(
                    "결제 결과 반영에 실패했다. reservationId={}, orderId={}, result={}",
                    reservationId,
                    command.orderId(),
                    outcome.result(),
                    e);
        }
        return transactions.read(reservationId);
    }

    private ConfirmOutcome requestConfirm(ConfirmCommand command, long amount) {
        try {
            return paymentPort.confirm(command.orderId(), command.pgAuthToken(), amount);
        } catch (RuntimeException e) {
            log.warn("결제 승인 결과를 모른다. 결과 확인 스케줄러가 마무리한다. orderId={}", command.orderId(), e);
            return ConfirmOutcome.unknown();
        }
    }
}
