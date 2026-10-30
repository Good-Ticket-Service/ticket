package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup.Status;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileTransactions;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.ProcessCrash;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookingSchedulerIntegrationTest extends BookingIntegrationTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final Duration THRESHOLD = Duration.ofMinutes(1);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Autowired
    private PaymentReconcileService reconcileService;

    @Autowired
    private PaymentReconcileTransactions reconcileTransactions;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    private Long hold(Duration holdFor, Long seatId) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(seatId),
                clock.now().plus(holdFor)));
    }

    /** 승인 결과를 모르는 채로 결제 중이 된 예약. 결제 중이 된 시각은 지금 clock이다. */
    private String paymentInProgress(Long reservationId) {
        String orderId = checkout(reservationId);
        payment.confirmTimesOut();
        confirm(reservationId, orderId);
        payment.confirmReturns(ConfirmOutcome.succeeded());
        payment.confirmedOrderIds.clear();
        payment.calledInTransaction.clear();
        return orderId;
    }

    private double gauge(String meter) {
        return meterRegistry.get(meter).gauge().value();
    }

    @Test
    @DisplayName("만료 스케줄러는 시간이 지난 HOLD만 EXPIRED로 바꾸고 좌석을 잠근 뒤 돌려준다")
    void expiresDueHolds() {
        Long seat = TestIds.next();
        Long expired = hold(Duration.ofMinutes(1), seat);
        Long alive = hold(Duration.ofMinutes(30), TestIds.next());
        Long paying = hold(Duration.ofMinutes(10), TestIds.next());
        paymentInProgress(paying);
        clock.advance(Duration.ofMinutes(15));
        double before = count("reservation.expired");

        int result = holdExpiryService.expireDue(10, BUDGET);

        assertThat(result).isEqualTo(1);
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(statusOf(alive)).isEqualTo("HOLD");
        assertThat(statusOf(paying)).as("결제 중 예약은 만료하지 않는다").isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(seats.locked).containsExactly(List.of(seat));
        assertThat(seats.released).containsExactly(List.of(seat));
        assertThat(seats.releasedFor).as("이 예약이 잡은 좌석만 돌려준다").containsExactly(expired);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT ticket_code FROM p_tickets WHERE reservation_id = ?", String.class, expired))
                .as("만료돼도 티켓 행은 남고, 코드는 발급되지 않는다")
                .containsExactly((String) null);
        assertThat(count("reservation.expired")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 만료되고, 실패한 건은 다음 실행의 후보로 남는다")
    void oneFailureDoesNotStopOthers() {
        Long failingSeat = TestIds.next();
        Long failing = hold(Duration.ofMinutes(1), failingSeat);
        Long other = hold(Duration.ofMinutes(2), TestIds.next());
        seats.failOnLock(failingSeat);
        clock.advance(Duration.ofMinutes(5));

        assertThat(holdExpiryService.expireDue(10, BUDGET)).isEqualTo(1);

        assertThat(statusOf(failing)).isEqualTo("HOLD");
        assertThat(statusOf(other)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("만료 스케줄러 두 개가 같은 예약을 동시에 잠그고 바꿔도 한 번만 만료하고 좌석도 한 번만 돌려준다")
    void concurrentExpiry() throws Exception {
        for (int i = 0; i < 5; i++) {
            hold(Duration.ofMinutes(1), TestIds.next());
        }
        clock.advance(Duration.ofMinutes(5));

        seats.syncLocksWith(new CyclicBarrier(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> holdExpiryService.expireDue(10, BUDGET)));
        }
        int expired = 0;
        for (Future<Integer> future : futures) {
            expired += future.get();
        }
        executor.shutdown();

        assertThat(expired).isEqualTo(5);
        assertThat(seats.released).hasSize(5);
    }

    @Test
    @DisplayName("결과 확인은 기준 시간 전의 결제 중 예약을 고르지 않는다")
    void respectsThreshold() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        paymentInProgress(reservationId);
        clock.advance(Duration.ofSeconds(30));

        assertThat(reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET))
                .isZero();

        assertThat(payment.lookedUpOrderIds).isEmpty();
    }

    @Test
    @DisplayName("인스턴스 두 대가 같은 예약을 고르면 한 대만 집어 payment를 조회한다")
    void onlyOneInstanceClaims() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        String orderId = paymentInProgress(reservationId);
        clock.advance(Duration.ofMinutes(2));

        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);
        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);

        assertThat(payment.lookedUpOrderIds).containsExactly(orderId);
    }

    @Test
    @DisplayName("인스턴스 두 대가 동시에 돌아도 예약마다 한 번만 payment를 조회한다")
    void concurrentReconcileLooksUpOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            paymentInProgress(hold(Duration.ofMinutes(10), TestIds.next()));
        }
        clock.advance(Duration.ofMinutes(2));

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);
            }));
        }
        for (Future<Integer> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertThat(payment.lookedUpOrderIds).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("고른 뒤 새 결제 시도가 시작된 예약은 지난 확인 시각이 오래됐어도 집지 않는다. 진행 중인 승인을 다시 보내면 안 된다")
    void doesNotClaimNewAttempt() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        paymentInProgress(reservationId);
        LocalDateTime selectedAt = clock.now();
        clock.advance(Duration.ofMinutes(2));
        jdbcTemplate.update(
                "UPDATE p_reservations SET payment_checked_at = ?, updated_at = ? WHERE reservation_id = ?",
                selectedAt.minusMinutes(5),
                clock.now(),
                reservationId);

        assertThat(reconcileTransactions.claimPendingPayment(
                        reservationId, clock.now().minus(THRESHOLD)))
                .isEmpty();
    }

    @Test
    @DisplayName("환불 대상으로 기록된 예약은 결과 확인이 집지 않아 환불 차례가 밀리지 않는다")
    void doesNotClaimRefundRequired() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        paymentInProgress(reservationId);
        clock.advance(Duration.ofMinutes(2));
        jdbcTemplate.update(
                "UPDATE p_reservations SET refund_required_at = ? WHERE reservation_id = ?",
                clock.now(),
                reservationId);

        assertThat(reconcileTransactions.claimPendingPayment(
                        reservationId, clock.now().minus(THRESHOLD)))
                .isEmpty();
    }

    @Test
    @DisplayName("payment가 승인 요청을 받지 못했으면 같은 orderId와 인증값으로 다시 보내고 결과를 반영한다")
    void resendsWhenNotReceived() {
        Long reservationId = hold(Duration.ofMinutes(10), TestIds.next());
        String orderId = paymentInProgress(reservationId);
        payment.lookupReturns(orderId, PaymentLookup.of(Status.NOT_RECEIVED));
        clock.advance(Duration.ofMinutes(2));
        double before = count("reservation.payment.confirm.resent");

        assertThat(reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET))
                .isEqualTo(1);

        assertThat(payment.confirmedOrderIds).containsExactly(orderId);
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(count("reservation.payment.confirm.resent")).isEqualTo(before + 1);
        assertThat(payment.calledInTransaction).as("조회와 재요청은 트랜잭션 밖이다").containsOnly(false);
    }

    @Test
    @DisplayName("조회 결과가 끝났으면 그대로 반영하고, 진행 중이거나 모르는 주문이면 그대로 둔다")
    void appliesFinishedLookups() {
        Long succeeded = hold(Duration.ofMinutes(10), TestIds.next());
        Long failed = hold(Duration.ofMinutes(10), TestIds.next());
        Long inProgress = hold(Duration.ofMinutes(10), TestIds.next());
        Long unknown = hold(Duration.ofMinutes(10), TestIds.next());
        payment.lookupReturns(paymentInProgress(succeeded), PaymentLookup.of(Status.SUCCEEDED));
        payment.lookupReturns(paymentInProgress(failed), PaymentLookup.of(PaymentLookup.Status.FAILED));
        payment.lookupReturns(paymentInProgress(inProgress), PaymentLookup.of(Status.IN_PROGRESS));
        payment.lookupReturns(paymentInProgress(unknown), PaymentLookup.of(Status.UNKNOWN_ORDER));
        clock.advance(Duration.ofMinutes(2));

        assertThat(reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET))
                .isEqualTo(2);

        assertThat(statusOf(succeeded)).isEqualTo("CONFIRMED");
        assertThat(statusOf(failed)).as("선점 시간이 남았다").isEqualTo("HOLD");
        assertThat(statusOf(inProgress)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(statusOf(unknown)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(payment.confirmedOrderIds).as("끝난 결과가 있으면 다시 보내지 않는다").isEmpty();
    }

    @Test
    @DisplayName("결제 중으로 커밋한 뒤 승인 요청을 보내기 전에 서버가 꺼지면, 결과 확인이 같은 승인을 한 번 보내 확정한다")
    void recoversWhenRestartedBeforeConfirmRequest() {
        Long seatId = TestIds.next();
        Long reservationId = hold(Duration.ofMinutes(10), seatId);
        String orderId = checkout(reservationId);
        payment.crashBeforeConfirmArrives();

        assertThatThrownBy(() -> confirm(reservationId, orderId)).isInstanceOf(ProcessCrash.class);
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(payment.confirmedOrderIds).as("payment는 승인 요청을 받지 못했다").isEmpty();

        payment.lookupReturns(orderId, PaymentLookup.of(Status.NOT_RECEIVED));
        clock.advance(Duration.ofMinutes(2));
        assertThat(reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET))
                .isEqualTo(1);

        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(payment.confirmedOrderIds).as("다시 보낸 승인 한 번").containsExactly(orderId);
        assertThat(seats.sold).containsExactly(List.of(seatId));
        assertThat(issuedTicketCodes(reservationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("payment가 승인한 뒤 결과를 반영하기 전에 서버가 꺼지면, 결과 확인이 조회한 성공을 반영하고 승인을 다시 보내지 않는다")
    void recoversWhenRestartedAfterConfirmApproved() {
        Long seatId = TestIds.next();
        Long reservationId = hold(Duration.ofMinutes(10), seatId);
        String orderId = checkout(reservationId);
        payment.crashAfterConfirmApproved();
        double lateSuccessBefore = lateSuccessCount();

        assertThatThrownBy(() -> confirm(reservationId, orderId)).isInstanceOf(ProcessCrash.class);
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(seats.sold).as("반영 전에 꺼져 좌석은 아직 팔리지 않았다").isEmpty();

        payment.lookupReturns(orderId, PaymentLookup.of(Status.SUCCEEDED));
        clock.advance(Duration.ofMinutes(2));
        assertThat(reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET))
                .isEqualTo(1);

        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(payment.confirmedOrderIds).as("꺼지기 전 승인 한 번뿐이다").containsExactly(orderId);
        assertThat(seats.sold).containsExactly(List.of(seatId));
        assertThat(issuedTicketCodes(reservationId)).isEqualTo(1);
        assertThat(lateSuccessCount()).as("확정했으므로 환불 대상이 아니다").isEqualTo(lateSuccessBefore);
    }

    private String checkout(Long reservationId) {
        return paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
    }

    private void confirm(Long reservationId, String orderId) {
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
    }

    private Integer issuedTicketCodes(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_tickets WHERE reservation_id = ? AND ticket_code IS NOT NULL",
                Integer.class,
                reservationId);
    }

    @Test
    @DisplayName("결제 중 예약 수와 상한을 넘긴 예약 수를 게이지로 남기고, 상태는 바꾸지 않는다")
    void reportsPaymentBacklog() {
        Long stuck = hold(Duration.ofMinutes(10), TestIds.next());
        paymentInProgress(stuck);
        clock.advance(Duration.ofMinutes(20));
        paymentInProgress(hold(Duration.ofMinutes(10), TestIds.next()));
        clock.advance(Duration.ofMinutes(11));

        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);
        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);

        assertThat(gauge("reservation.payment.in_progress")).isEqualTo(2);
        assertThat(gauge("reservation.payment.stuck")).as("실행을 반복해도 예약 수를 센다").isEqualTo(1);
        assertThat(statusOf(stuck)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("결과가 안 바뀌는 예약이 한 묶음보다 많아도 새로 결과를 모르게 된 예약까지 차례가 온다")
    void doesNotStarveOnUnchangedPayments() {
        for (int i = 0; i < 3; i++) {
            paymentInProgress(hold(Duration.ofMinutes(10), TestIds.next()));
            clock.advance(Duration.ofSeconds(1));
        }
        Long newest = hold(Duration.ofMinutes(10), TestIds.next());
        payment.lookupReturns(paymentInProgress(newest), PaymentLookup.of(Status.SUCCEEDED));
        clock.advance(Duration.ofMinutes(2));

        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 2, BUDGET);
        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 2, BUDGET);

        assertThat(statusOf(newest)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("한 번 실행은 시간 예산을 넘기면 멈추고 남은 건을 다음 실행에 넘긴다")
    void stopsAtTimeBudget() {
        for (int i = 0; i < 3; i++) {
            paymentInProgress(hold(Duration.ofMinutes(10), TestIds.next()));
        }
        clock.advance(Duration.ofMinutes(2));
        payment.lookupDelay(Duration.ofMillis(300));

        long started = System.nanoTime();
        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, Duration.ofMillis(450));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(payment.lookedUpOrderIds).hasSize(2);
        assertThat(elapsed).as("예산 450ms에 진행 중이던 조회 하나(300ms)만큼만 넘친다").isLessThan(Duration.ofMillis(1_000));
    }
}
