package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutResult;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions.CheckoutTarget;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions.PaymentStart;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentCallGuard;
import com.sparta.ticket.reservation.booking.application.port.PaymentCallGuard.Call;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
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
 * payment가 응답하지 않는 동안은 PaymentCallGuard가 두 호출을 상태를 바꾸기 전에 503으로 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPaymentService {

    private final PaymentRequestTransactions transactions;
    private final PaymentResultApplier resultApplier;
    private final PaymentPort paymentPort;
    private final PaymentCallGuard paymentCallGuard;
    private final PaymentConfirmSettings confirmSettings;
    private final ConfirmRouter confirmRouter;
    private final AdmissionGate admissionGate;
    private final BookingMetrics metrics;

    /**
     * 결제를 준비한다. 상태는 HOLD 그대로이고, 고른 쿠폰을 예약에 잡은 뒤 새 orderId와 결제 금액을 기록한다.
     * 결제 호출이 실패하거나 결과를 몰라도 잡은 쿠폰은 그대로 둔다. 같은 쿠폰으로 다시 준비하면 된다.
     * 막혀 있으면 쿠폰을 잡기 전에 503으로 끝낸다.
     */
    public CheckoutResult checkout(UserContext user, Long reservationId, CheckoutCommand command) {
        // 이 준비 뒤의 승인이 메시지로 갈 때만 접수 제한을 본다. 승인 요청이 나가지 못해 동기로 돌리는 동안에도 보면,
        // 이미 묶인 요청이 시한까지 늦은 결제로 세어져 결제 준비를 몇 분 막고 동기로 돌린 승인을 쓸 사용자가 들어오지 못한다
        if (confirmRouter.route().byMessage()) {
            admit(reservationId);
        }
        Call call = paymentCallGuard.start();
        try {
            CheckoutTarget target =
                    applyCoupon(transactions.loadCheckoutTarget(user.userId(), reservationId), command.userCouponId());
            String orderId = UUID.randomUUID().toString();
            PreparedPayment prepared = prepare(
                    call,
                    new PaymentPrepareRequest(
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
        } catch (Throwable e) {
            // payment를 부르기 전에 끝났으면 받은 자리를 돌려준다. 이미 결과를 기록한 호출이면 아무것도 하지 않는다
            call.skipped();
            throw e;
        }
    }

    /**
     * 메시지 승인은 승인을 바로 받으므로 결제 쪽이 밀려도 예매는 모른 채 결제 중을 쌓는다. 결과가 계속 늦어지면 결제창에 들어가기 전인
     * 결제 준비에서 막는다. 승인에서 막으면 사용자가 받은 인증값을 버려야 한다. 동기 승인은 승인 호출 보호가 같은 일을 한다.
     * 받는 동안은 요청마다 DB를 더 보지 않는다. 거절 중에는 만료 임박 통과 몫이 남았을 때만 선점 만료 시각 한 칸을 읽는다.
     * 거절하는 원인이 DB 포화일 수 있어 거절에 드는 DB 비용을 줄인다. 본인 확인과 상태 검사는 통과한 뒤 본 흐름이 한다.
     */
    private void admit(Long reservationId) {
        if (admissionGate.tryAdmit()) {
            return;
        }
        if (admissionGate.hasPriorityRoom()
                && transactions
                        .holdExpiresAt(reservationId)
                        .filter(admissionGate::letsThroughBeforeHoldExpires)
                        .isPresent()) {
            return;
        }
        throw admissionGate.reject();
    }

    private PreparedPayment prepare(Call call, PaymentPrepareRequest request) {
        try {
            PreparedPayment prepared = paymentPort.prepare(request);
            call.answered();
            return prepared;
        } catch (Throwable e) {
            recordFailure(call, e);
            throw e;
        }
    }

    /**
     * payment가 4xx 업무 오류로 답했으면 답한 것이다. 5xx와 그 밖의 예외(타임아웃, 연결 실패)는 응답하지 않은 것으로 센다.
     * 어댑터는 연결 실패·타임아웃과 PG의 준비 거절을 같은 503으로 바꾸므로 드문 PG 준비 거절도 응답 없음으로 세어진다.
     * 받은 허가는 Error가 나도 반드시 기록한다. 기록 없이 두면 반쯤 열린 상태에서 시험 호출 자리가 바닥나 결제가 계속 막힌다.
     */
    private static void recordFailure(Call call, Throwable e) {
        if (e instanceof BusinessException business
                && !business.getErrorCode().status().is5xxServerError()) {
            call.answered();
        } else {
            call.unanswered(e);
        }
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
     *
     * 막혀 있으면 결제 중으로 바꾸기 전에 503으로 끝낸다. 예약은 HOLD 그대로라 선점 시간 안에 같은 인증값으로 다시 승인할 수 있다.
     * 결제 중으로 바꾼 뒤에 막으면 결과 모름이 되어 사용자가 결과 확인 스케줄러를 1~2분 기다린다.
     * 같은 승인이 다시 와도 막혀 있는 동안은 503이다. 지금 상태는 조회 API로 본다.
     *
     * 메시지로 요청할지 동기로 부를지는 결제 중으로 바꾸기 전에 한 번만 정한다. 메시지로 남긴 뒤 보내지 못했다고 동기로 다시 부르면
     * 한 orderId가 두 길로 간다. 이미 메시지로 접수한 예약에 같은 승인이 다시 와서 동기로 가도, 결제 시작이 HOLD만 바꾸므로
     * payment를 부르지 않고 지금 상태를 돌려준다.
     */
    public ReservationResult confirm(UserContext user, Long reservationId, ConfirmCommand command) {
        ConfirmRoute route = confirmRouter.route();
        if (route.byMessage()) {
            return confirmByMessage(user, reservationId, command);
        }
        Call call = paymentCallGuard.start();
        try {
            PaymentStart start = transactions.startPayment(user.userId(), reservationId, command);
            if (!start.started()) {
                call.skipped();
                return start.current();
            }
            if (route == ConfirmRoute.SYNC_FALLBACK) {
                metrics.fallbackConfirm();
            }
            ConfirmOutcome outcome = requestConfirm(call, command, start.amount());
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
        } catch (Throwable e) {
            // payment를 부르기 전에 끝났으면 받은 자리를 돌려준다. 이미 결과를 기록한 호출이면 아무것도 하지 않는다
            call.skipped();
            throw e;
        }
    }

    /**
     * 결제 중으로 바꾸며 승인 요청을 같은 트랜잭션에 남기고 바로 답한다. payment를 기다리지 않으므로 요청 스레드를 PG 시간만큼 잡지 않고,
     * 결제 호출 보호(PaymentCallGuard)를 거치지 않아 payment가 느려도 503으로 돌려보내지 않는다. 결과는 결과 메시지로 받는다.
     */
    private ReservationResult confirmByMessage(UserContext user, Long reservationId, ConfirmCommand command) {
        return transactions
                .startPaymentByMessage(user.userId(), reservationId, command, confirmSettings.window())
                .current();
    }

    private ConfirmOutcome requestConfirm(Call call, ConfirmCommand command, long amount) {
        try {
            ConfirmOutcome outcome = paymentPort.confirm(command.orderId(), command.pgAuthToken(), amount);
            // 결과 모름은 payment가 PG 결과를 기다리다 처리 중으로 답한 것이다. PG가 멈추면 이 답만 이어지므로 응답 없음으로 센다.
            // 답함으로 세면 PG가 멈춰도 막지 못해 승인마다 결제 중으로 바뀐 뒤 결과 모름으로 쌓인다
            switch (outcome.result()) {
                case SUCCEEDED, FAILED -> call.answered();
                case UNKNOWN ->
                    call.unanswered(
                            new IllegalStateException("payment가 승인 결과를 모른다고 답했다. orderId=" + command.orderId()));
            }
            return outcome;
        } catch (RuntimeException e) {
            recordFailure(call, e);
            log.warn("결제 승인 결과를 모른다. 결과 확인 스케줄러가 마무리한다. orderId={}", command.orderId(), e);
            return ConfirmOutcome.unknown();
        } catch (Error e) {
            call.unanswered(e);
            throw e;
        }
    }
}
