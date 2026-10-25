package com.sparta.ticket.reservation.application;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.application.ReservationTransactions.CheckoutTarget;
import com.sparta.ticket.reservation.application.ReservationTransactions.PaymentStart;
import com.sparta.ticket.reservation.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.application.dto.CheckoutResult;
import com.sparta.ticket.reservation.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.application.dto.ReservationResult;
import com.sparta.ticket.reservation.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.application.port.PaymentPort;
import com.sparta.ticket.reservation.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.application.port.PreparedPayment;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 준비와 승인 유스케이스. 일부러 @Transactional을 붙이지 않는다. payment 호출은 트랜잭션 밖에서 하고,
 * 앞뒤의 DB 변경은 ReservationTransactions와 PaymentResultApplier가 각자의 트랜잭션으로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPaymentService {

    private final ReservationTransactions transactions;
    private final PaymentResultApplier resultApplier;
    private final PaymentPort paymentPort;

    /** 결제를 준비한다. 상태는 HOLD 그대로이고, 새 orderId와 결제 금액을 기록한다. */
    public CheckoutResult checkout(UserContext user, UUID reservationId, CheckoutCommand command) {
        CheckoutTarget target = transactions.loadCheckoutTarget(user.userId(), reservationId);
        String orderId = UUID.randomUUID().toString();
        PreparedPayment prepared = paymentPort.prepare(new PaymentPrepareRequest(
                orderId,
                target.reservationId(),
                target.userId(),
                target.baseAmount(),
                command.couponId(),
                command.pgProvider(),
                target.holdExpiresAt()));
        transactions.recordCheckout(target, orderId, prepared.amount());
        return new CheckoutResult(orderId, prepared.amount(), prepared.paymentWindowUrl());
    }

    /**
     * 결제를 승인한다. 결과를 모르면 PAYMENT_IN_PROGRESS로 두고 응답하며, 결과 확인 스케줄러가 마무리한다.
     * 사용자 요청 경로에서는 승인을 다시 보내지 않는다. 기다리는 동안 요청 스레드를 붙잡지 않기 위해서다.
     */
    public ReservationResult confirm(UserContext user, UUID reservationId, ConfirmCommand command) {
        PaymentStart start = transactions.startPayment(user.userId(), reservationId, command);
        if (!start.started()) {
            return start.current();
        }
        ConfirmOutcome outcome = requestConfirm(command, start.amount());
        try {
            resultApplier.apply(reservationId, command.orderId(), outcome);
        } catch (RuntimeException e) {
            // Note: 승인은 성공했을 수 있는데 반영만 실패했다(F5). 오류로 답하면 클라이언트는 결제 실패로 안다.
            // 예약은 결제 중 그대로이므로 그 상태를 돌려주고, 결과 확인 스케줄러가 조회해 다시 반영한다.
            log.error(
                    "결제 결과 반영에 실패했다(F5). reservationId={}, orderId={}, result={}",
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
