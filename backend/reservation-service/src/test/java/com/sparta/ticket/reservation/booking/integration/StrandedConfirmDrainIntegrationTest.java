package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.payment.StrandedConfirmDrain;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup.Status;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Accepted;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Busy;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.NotReceived;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Rejected;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Settled;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentBacklogService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxPublisher;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.SwitchableConfirmSettings;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 승인 요청이 브로커로 나가지 못하는 동안 이미 Outbox에 남은 요청을 HTTP 대체 승인으로 보내는 작업자. 커밋 직후 발행이 꺼져 있어
 * 메시지로 남긴 요청은 브로커가 받지 못한 것처럼 Outbox에 남는다. 작업자 스레드는 없고 테스트가 sendNext를 직접 부른다.
 */
class StrandedConfirmDrainIntegrationTest extends BookingIntegrationTest {

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private PaymentBacklogService backlogService;

    @Autowired
    private StrandedConfirmDrain drain;

    @Autowired
    private PaymentReconcileService reconcileService;

    @Autowired
    private OutboxPublisher publisher;

    private static final Duration RESEND_AFTER = SwitchableConfirmSettings.RESEND_AFTER;
    private static final String DRAIN_METER = "reservation.payment.confirm.drain";

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    private record Attempt(Long reservationId, String orderId) {}

    private Attempt checkedOut() {
        Long reservationId = transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        return new Attempt(reservationId, orderId);
    }

    private ReservationResult confirm(Attempt attempt) {
        return paymentService.confirm(
                user, attempt.reservationId(), new ConfirmCommand(attempt.orderId(), "pk-" + attempt.orderId()));
    }

    private Attempt requestedByMessage() {
        confirmSettings.useMessage();
        Attempt attempt = checkedOut();
        confirm(attempt);
        return attempt;
    }

    /** 1초마다 도는 결제 중 집계를, 시계를 움직인 뒤 한 번 돌린다. 맨 앞 요청이 기준 시간을 기다렸으면 새 승인을 동기로 돌린다. */
    private void refreshAfter(Duration elapsed) {
        clock.advance(elapsed);
        backlogService.refresh();
    }

    private int outboxSize() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_booking_outbox", Integer.class);
    }

    /** 승인 요청 하나를 메시지로 남기고, 맨 앞 요청이 기준 시간을 기다리게 해 새 승인을 동기로 돌린다. 대신 보내기는 켠다. */
    private Attempt strandedWhileFallingBack() {
        confirmSettings.enableDrain();
        Attempt stranded = requestedByMessage();
        refreshAfter(SwitchableConfirmSettings.UNSENT_AFTER);
        return stranded;
    }

    private double drainCount(String result) {
        return meterRegistry.counter(DRAIN_METER, "result", result).count();
    }

    private LocalDateTime confirmBeforeOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT confirm_before FROM p_reservations WHERE reservation_id = ?",
                LocalDateTime.class,
                reservationId);
    }

    @Test
    @DisplayName("승인 요청이 나가지 못하는 동안 Outbox에 남은 요청을 HTTP로 대신 보내 확정하고, 행은 발행기 몫으로 남긴다")
    void drainsStrandedRequestOverHttp() {
        confirmSettings.enableDrain();
        Attempt stranded = requestedByMessage();
        refreshAfter(SwitchableConfirmSettings.UNSENT_AFTER);
        substitute.answers(stranded.orderId(), new Settled(ConfirmOutcome.succeeded()));

        boolean worked = drain.sendNext();

        assertThat(worked).isTrue();
        assertThat(statusOf(stranded.reservationId())).isEqualTo("CONFIRMED");
        assertThat(substitute.received).singleElement().satisfies(request -> {
            assertThat(request.orderId()).isEqualTo(stranded.orderId());
            assertThat(request.reservationId()).isEqualTo(stranded.reservationId());
            assertThat(request.pgAuthToken()).isEqualTo("pk-" + stranded.orderId());
            assertThat(request.confirmBefore()).isEqualTo(confirmBeforeOf(stranded.reservationId()));
        });
        assertThat(substitute.calledInTransaction).containsExactly(false);
        assertThat(payment.confirmedOrderIds).isEmpty();
        assertThat(outboxSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("묶인 요청을 모두 끝낸 뒤에도 Outbox 행이 남아 있어, 브로커가 멈춘 동안 새 승인은 계속 동기로 부른다")
    void keepsFallingBackAfterDraining() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Settled(ConfirmOutcome.succeeded()));
        drain.sendNext();
        refreshAfter(Duration.ofSeconds(1));
        Attempt next = checkedOut();

        ReservationResult result = confirm(next);

        assertThat(result.status().name()).isEqualTo("CONFIRMED");
        assertThat(payment.confirmedOrderIds).containsExactly(next.orderId());
        assertThat(outboxSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("대신 보내기를 끄면 동기로 돌리는 중이어도 묶인 요청을 보내지 않는다")
    void sendsNothingWhenDisabled() {
        requestedByMessage();
        refreshAfter(SwitchableConfirmSettings.UNSENT_AFTER);

        assertThat(drain.sendNext()).isFalse();

        assertThat(substitute.received).isEmpty();
    }

    @Test
    @DisplayName("승인 요청이 브로커로 나가고 있으면 Outbox에 남은 요청은 발행기 몫이라 대신 보내지 않는다")
    void sendsNothingWhileRequestsLeave() {
        confirmSettings.enableDrain();
        requestedByMessage();
        refreshAfter(SwitchableConfirmSettings.UNSENT_AFTER.minusSeconds(1));

        assertThat(drain.sendNext()).isFalse();

        assertThat(substitute.received).isEmpty();
    }

    @Test
    @DisplayName("시한이 이른 요청부터 보내고, 승인 요청이 아닌 Outbox 행은 건너뛴다")
    void sendsEarliestDeadlineFirst() {
        confirmSettings.enableDrain();
        jdbcTemplate.update(
                "INSERT INTO p_booking_outbox (topic, message_key, payload, created_at) VALUES (?, ?, ?, ?)",
                "other.topic",
                "not-a-reservation-id",
                "not json",
                clock.now());
        Attempt first = requestedByMessage();
        clock.advance(Duration.ofSeconds(1));
        Attempt second = requestedByMessage();
        refreshAfter(SwitchableConfirmSettings.UNSENT_AFTER);

        assertThat(drain.sendNext()).isTrue();
        assertThat(drain.sendNext()).isTrue();
        assertThat(drain.sendNext()).isFalse();

        assertThat(substitute.receivedOrderIds()).containsExactly(first.orderId(), second.orderId());
    }

    @Test
    @DisplayName("시한이 지난 요청은 보내지 않는다")
    void neverSendsPastDeadline() {
        confirmSettings.enableDrain();
        Attempt expired = requestedByMessage();
        refreshAfter(SwitchableConfirmSettings.WINDOW);

        assertThat(drain.sendNext()).isFalse();

        assertThat(substitute.received).isEmpty();
        assertThat(statusOf(expired.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("payment가 받았다고만 답하면 결제 중으로 두고, 다시 볼 간격이 지난 뒤 승인을 다시 보내지 않고 결제 조회로 결과를 물어 확정한다")
    void looksUpAfterAccepted() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.lookupAnswers(stranded.orderId(), new Settled(ConfirmOutcome.succeeded()));
        double accepted = drainCount("accepted");

        assertThat(drain.sendNext()).isTrue();
        assertThat(statusOf(stranded.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        clock.advance(RESEND_AFTER.minusMillis(1));
        assertThat(drain.sendNext()).isFalse();
        assertThat(substitute.lookedUp).isEmpty();

        clock.advance(Duration.ofMillis(1));
        assertThat(drain.sendNext()).isTrue();

        assertThat(statusOf(stranded.reservationId())).isEqualTo("CONFIRMED");
        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId());
        assertThat(substitute.lookedUpOrderIds()).containsExactly(stranded.orderId());
        assertThat(drainCount("accepted")).isEqualTo(accepted + 1);
    }

    @Test
    @DisplayName("결제 조회에서 결과를 기다리는 중이면 승인을 다시 보내지 않고 다시 볼 간격마다 조회를 되풀이한다")
    void keepsLookingUpWhileInProgress() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.lookupAnswers(stranded.orderId(), new Accepted(), new Settled(ConfirmOutcome.succeeded()));

        drain.sendNext();
        clock.advance(RESEND_AFTER);
        drain.sendNext();
        clock.advance(RESEND_AFTER);
        drain.sendNext();

        assertThat(statusOf(stranded.reservationId())).isEqualTo("CONFIRMED");
        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId());
        assertThat(substitute.lookedUpOrderIds()).containsExactly(stranded.orderId(), stranded.orderId());
    }

    @Test
    @DisplayName("결제 조회에서 payment가 승인을 받은 적이 없다고 하면 승인을 바로 다시 보낸다")
    void sendsAgainWhenPaymentNeverReceived() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.lookupAnswers(stranded.orderId(), new NotReceived());
        substitute.answers(stranded.orderId(), new Accepted(), new Settled(ConfirmOutcome.succeeded()));
        double notReceived = drainCount("not_received");

        drain.sendNext();
        clock.advance(RESEND_AFTER);
        assertThat(drain.sendNext()).isTrue();
        assertThat(drain.sendNext()).isTrue();

        assertThat(statusOf(stranded.reservationId())).isEqualTo("CONFIRMED");
        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId(), stranded.orderId());
        assertThat(substitute.lookedUpOrderIds()).containsExactly(stranded.orderId());
        assertThat(drainCount("not_received")).isEqualTo(notReceived + 1);
    }

    @Test
    @DisplayName("결제 조회가 주문을 모른다고 하면 다시 보내지 않고, 시한 뒤 결과 확인이 지금처럼 끝낸다")
    void parksWhenLookupRejects() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.lookupAnswers(stranded.orderId(), new Rejected("PAYMENT_NOT_FOUND"));

        drain.sendNext();
        clock.advance(RESEND_AFTER);
        assertThat(drain.sendNext()).isTrue();
        clock.advance(RESEND_AFTER.multipliedBy(10));
        assertThat(drain.sendNext()).isFalse();

        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId());
        assertThat(substitute.lookedUpOrderIds()).containsExactly(stranded.orderId());
        assertThat(statusOf(stranded.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("payment가 받을 자리가 없다며 돌려보내면 알려 준 시간 동안 보내지 않고, 그 뒤 같은 요청을 다시 보낸다")
    void pausesWhilePaymentIsBusy() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Busy(Duration.ofSeconds(10)));
        double busy = drainCount("busy");

        assertThat(drain.sendNext()).isTrue();
        clock.advance(Duration.ofSeconds(9));
        assertThat(drain.sendNext()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(drain.sendNext()).isTrue();

        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId(), stranded.orderId());
        assertThat(substitute.lookedUp).isEmpty();
        assertThat(statusOf(stranded.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(drainCount("busy")).isEqualTo(busy + 1);
    }

    @Test
    @DisplayName("payment가 바로 다시 보내라고 해도 1초는 쉰다")
    void pausesAtLeastOneSecond() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Busy(Duration.ZERO));

        drain.sendNext();
        clock.advance(Duration.ofMillis(999));
        assertThat(drain.sendNext()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(drain.sendNext()).isTrue();

        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId(), stranded.orderId());
    }

    @Test
    @DisplayName("돌려보낸 요청은 쉬는 시간이 끝나면 다시 보낼 간격을 기다리지 않고 바로 보낸다")
    void resendsBusyRequestRightAfterPause() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Busy(Duration.ofSeconds(1)));

        drain.sendNext();
        clock.advance(Duration.ofSeconds(1));

        assertThat(drain.sendNext()).isTrue();
        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId(), stranded.orderId());
    }

    @Test
    @DisplayName("payment가 상한보다 오래 쉬라고 해도 상한만큼만 쉰다")
    void capsPauseAtMaxBackoff() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Busy(Duration.ofHours(1)));

        drain.sendNext();
        clock.advance(SwitchableConfirmSettings.MAX_BACKOFF);

        assertThat(drain.sendNext()).isTrue();
        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId(), stranded.orderId());
    }

    @Test
    @DisplayName("payment가 받지 않는 요청은 다시 보내지 않고, 시한 뒤 결과 확인이 지금처럼 끝낸다")
    void leavesRejectedRequestToReconcile() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Rejected("conflict"));
        double rejected = drainCount("rejected");

        assertThat(drain.sendNext()).isTrue();
        clock.advance(RESEND_AFTER.multipliedBy(10));
        assertThat(drain.sendNext()).isFalse();
        assertThat(substitute.received).hasSize(1);

        clock.set(confirmBeforeOf(stranded.reservationId())
                .plus(SwitchableConfirmSettings.GRACE)
                .plusSeconds(1));
        payment.lookupReturns(stranded.orderId(), PaymentLookup.of(Status.NOT_RECEIVED));
        reconcileService.reconcile(Duration.ofMinutes(1), Duration.ofMinutes(30), 10, Duration.ofSeconds(5));

        assertThat(statusOf(stranded.reservationId())).isEqualTo("HOLD");
        assertThat(drainCount("rejected")).isEqualTo(rejected + 1);
    }

    @Test
    @DisplayName("승인의 답을 받지 못하면 승인을 다시 보내지 않고, 다시 볼 간격이 지난 뒤 결제 조회로 결과를 물어 확정한다")
    void looksUpAfterNoAnswer() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.failsOnce(stranded.orderId());
        substitute.lookupAnswers(stranded.orderId(), new Settled(ConfirmOutcome.succeeded()));
        double unknown = drainCount("unknown");

        assertThat(drain.sendNext()).isTrue();
        assertThat(statusOf(stranded.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(drain.sendNext()).isFalse();
        clock.advance(RESEND_AFTER);
        assertThat(drain.sendNext()).isTrue();

        assertThat(substitute.receivedOrderIds()).containsExactly(stranded.orderId());
        assertThat(substitute.lookedUpOrderIds()).containsExactly(stranded.orderId());
        assertThat(statusOf(stranded.reservationId())).isEqualTo("CONFIRMED");
        assertThat(drainCount("unknown")).isEqualTo(unknown + 1);
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("작업자 둘이 동시에 보내도 묶인 요청을 하나씩 한 번만 보낸다")
    void concurrentDrainersSendEachOnce() throws Exception {
        confirmSettings.enableDrain();
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Attempt attempt = requestedByMessage();
            substitute.answers(attempt.orderId(), new Settled(ConfirmOutcome.succeeded()));
            orderIds.add(attempt.orderId());
        }
        refreshAfter(SwitchableConfirmSettings.UNSENT_AFTER);
        CountDownLatch release = new CountDownLatch(1);
        substitute.holdUntil(release);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    int sent = 0;
                    while (drain.sendNext()) {
                        sent++;
                    }
                    return sent;
                }));
            }
            start.countDown();
            // 두 작업자가 각자 고른 요청을 보내는 중에 붙잡혀 있다
            while (substitute.received.size() < 2) {
                Thread.sleep(10);
            }
            release.countDown();
            int sent = 0;
            for (Future<Integer> run : runs) {
                sent += run.get();
            }

            assertThat(sent).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(substitute.receivedOrderIds()).containsExactlyInAnyOrderElementsOf(orderIds);
    }

    @Test
    @DisplayName("대신 보내 확정한 요청을 발행기가 나중에 브로커로 보내고 지워도 예약은 확정 그대로다")
    void publisherSendsDrainedRowLater() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Settled(ConfirmOutcome.succeeded()));
        drain.sendNext();

        assertThat(publisher.publish(100, Duration.ofSeconds(10))).isEqualTo(1);

        assertThat(outboxSize()).isZero();
        assertThat(statusOf(stranded.reservationId())).isEqualTo("CONFIRMED");
        assertThat(drain.sendNext()).isFalse();
    }

    @Test
    @DisplayName("선점 시간 안에 결제 실패로 답하면 예약을 HOLD로 돌려 다시 결제할 수 있게 한다")
    void returnsToHoldOnFailure() {
        Attempt stranded = strandedWhileFallingBack();
        substitute.answers(stranded.orderId(), new Settled(ConfirmOutcome.failed()));

        assertThat(drain.sendNext()).isTrue();

        assertThat(statusOf(stranded.reservationId())).isEqualTo("HOLD");
    }
}
