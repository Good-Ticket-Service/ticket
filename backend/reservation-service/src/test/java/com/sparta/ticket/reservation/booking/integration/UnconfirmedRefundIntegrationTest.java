package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResultApplier;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundReason;
import com.sparta.ticket.reservation.booking.application.reconcile.UnconfirmedRefundService;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 결제는 성공했는데 좌석 때문에 확정하지 못한 예약을 환불하고 끝내는지 본다. */
class UnconfirmedRefundIntegrationTest extends BookingIntegrationTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(1);
    private static final Duration BUDGET = Duration.ofSeconds(5);

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private PaymentResultApplier resultApplier;

    @Autowired
    private UnconfirmedRefundService refundService;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);
    private final Long scheduleId = TestIds.next();

    private Long hold(Long seatId) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(scheduleId),
                TestSeats.priced(seatId),
                clock.now().plusMinutes(10)));
    }

    /** 결제는 성공했는데 좌석이 이 예약의 HOLD가 아니라 확정하지 못한 예약을 만든다. 쿠폰을 주면 잡은 채로 둔다. */
    private UnconfirmedPayment unconfirmedSuccess(Long userCouponId) {
        Long seatId = TestIds.next();
        Long reservationId = hold(seatId);
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(userCouponId, "TOSS"))
                .orderId();
        seats.notHeld(seatId);
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        assertThat(statusOf(reservationId)).as("확정하지 못해 결제 중으로 남는다").isEqualTo("PAYMENT_IN_PROGRESS");
        return new UnconfirmedPayment(reservationId, orderId);
    }

    private record UnconfirmedPayment(Long reservationId, String orderId) {}

    private int runRefunds() {
        return refundService.refund(THRESHOLD, 10, BUDGET);
    }

    private double refundedCount() {
        return count("reservation.payment.unconfirmed_refunded");
    }

    @Test
    @DisplayName("확정하지 못한 결제는 확정 실패 사유로 한 번 환불을 요청하고, payment가 받으면 예약을 취소로 끝낸다")
    void refundsAndCancels() {
        UnconfirmedPayment unconfirmed = unconfirmedSuccess(null);
        double before = refundedCount();
        clock.advance(Duration.ofMinutes(2));

        assertThat(runRefunds()).isEqualTo(1);

        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("CANCELED");
        assertThat(payment.refundedOrderIds).containsExactly(unconfirmed.orderId());
        assertThat(payment.refundReasons).containsExactly(RefundReason.CONFIRM_FAILED);
        assertThat(seats.sold).as("좌석은 이 예약의 것이 아니라 팔지 않는다").isEmpty();
        assertThat(refundedCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("환불을 받아 끝낸 예약은 다음 실행에서 다시 요청하지 않는다")
    void doesNotRefundTwice() {
        unconfirmedSuccess(null);
        clock.advance(Duration.ofMinutes(2));
        runRefunds();

        clock.advance(Duration.ofMinutes(2));
        assertThat(runRefunds()).isZero();

        assertThat(payment.refundedOrderIds).hasSize(1);
    }

    @Test
    @DisplayName("끝내면 잡아 둔 쿠폰을 돌려준다")
    void releasesCoupon() {
        Long coupon = coupons.issue(user.userId(), 3_000);
        unconfirmedSuccess(coupon);
        assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");
        clock.advance(Duration.ofMinutes(2));

        runRefunds();

        assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("끝내면 같은 회원이 같은 회차를 다시 선점할 수 있다")
    void freesActiveReservationSlot() {
        unconfirmedSuccess(null);
        clock.advance(Duration.ofMinutes(2));

        runRefunds();

        assertThat(hold(TestIds.next())).isNotNull();
    }

    @Test
    @DisplayName("환불 결과를 모르면 결제 중으로 두고, 기준 시간이 지난 뒤 같은 orderId로 다시 요청해 끝낸다")
    void retriesUnknownRefund() {
        UnconfirmedPayment unconfirmed = unconfirmedSuccess(null);
        payment.refundTimesOut();
        clock.advance(Duration.ofMinutes(2));

        assertThat(runRefunds()).isZero();
        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");

        payment.refundReturns(RefundOutcome.accepted());
        clock.advance(Duration.ofSeconds(30));
        assertThat(runRefunds()).as("기준 시간 전에는 다시 보내지 않는다").isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(runRefunds()).isEqualTo(1);

        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("CANCELED");
        assertThat(payment.refundedOrderIds).containsExactly(unconfirmed.orderId(), unconfirmed.orderId());
        assertThat(payment.refundReasons).containsOnly(RefundReason.CONFIRM_FAILED);
    }

    @Test
    @DisplayName("payment가 orderId를 모르면 결제 중으로 둔다")
    void keepsUnknownOrder() {
        UnconfirmedPayment unconfirmed = unconfirmedSuccess(null);
        payment.refundReturns(RefundOutcome.unknownOrder());
        clock.advance(Duration.ofMinutes(2));

        assertThat(runRefunds()).isZero();

        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("결과를 기다리는 보통의 결제 중 예약은 환불하지 않는다")
    void ignoresPendingPayment() {
        Long reservationId = hold(TestIds.next());
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        payment.confirmTimesOut();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        clock.advance(Duration.ofMinutes(2));

        assertThat(runRefunds()).isZero();

        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("환불 대상인 예약에 실패 결과가 늦게 와도 선점으로 돌아가지 않고, 환불로 끝낸다")
    void ignoresLateFailureWhileRefundPending() {
        UnconfirmedPayment unconfirmed = unconfirmedSuccess(null);

        resultApplier.apply(unconfirmed.reservationId(), unconfirmed.orderId(), ConfirmOutcome.failed());

        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        clock.advance(Duration.ofMinutes(2));
        assertThat(runRefunds()).isEqualTo(1);
        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("선점 시간이 지난 뒤 환불 대상인 예약에 실패 결과가 와도 만료로 끝내지 않는다")
    void ignoresLateFailureAfterExpiryWhileRefundPending() {
        UnconfirmedPayment unconfirmed = unconfirmedSuccess(null);
        clock.advance(Duration.ofMinutes(11));

        resultApplier.apply(unconfirmed.reservationId(), unconfirmed.orderId(), ConfirmOutcome.failed());

        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(runRefunds()).isEqualTo(1);
        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("환불로 끝낸 예약에 같은 성공이 다시 와도 환불 대상으로 다시 세지 않는다")
    void ignoresDuplicateSuccessAfterRefund() {
        UnconfirmedPayment unconfirmed = unconfirmedSuccess(null);
        clock.advance(Duration.ofMinutes(2));
        runRefunds();
        double lateBefore = lateSuccessCount();

        resultApplier.apply(unconfirmed.reservationId(), unconfirmed.orderId(), ConfirmOutcome.succeeded());

        assertThat(lateSuccessCount()).isEqualTo(lateBefore);
        assertThat(statusOf(unconfirmed.reservationId())).isEqualTo("CANCELED");
    }
}
