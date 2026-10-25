package com.sparta.ticket.reservation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.application.BookingFacade;
import com.sparta.ticket.reservation.application.HoldExpiryService;
import com.sparta.ticket.reservation.application.PaymentReconcileService;
import com.sparta.ticket.reservation.application.ReservationPaymentService;
import com.sparta.ticket.reservation.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.application.port.PaymentLookup;
import com.sparta.ticket.reservation.application.port.PaymentLookup.Status;
import com.sparta.ticket.reservation.support.FakePaymentPort;
import com.sparta.ticket.reservation.support.FakeSeatPort;
import com.sparta.ticket.reservation.support.MutableClock;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BookingSchedulerIntegrationTest extends ReservationIntegrationTest {

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
    private FakePaymentPort payment;

    @Autowired
    private FakeSeatPort seats;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UserContext user = new UserContext(UUID.randomUUID(), "hong01", Role.USER);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_reservation_seats");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(LocalDateTime.of(2026, 11, 1, 20, 0));
        payment.reset();
        seats.reset();
    }

    private UUID hold(Duration holdFor, UUID seatId) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(), UUID.randomUUID(), List.of(seatId), clock.now().plus(holdFor)));
    }

    /** 승인 결과를 모르는 채로 결제 중이 된 예약. 결제 중이 된 시각은 지금 clock이다. */
    private String paymentInProgress(UUID reservationId) {
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        payment.confirmTimesOut();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        payment.confirmReturns(ConfirmOutcome.succeeded());
        payment.confirmedOrderIds.clear();
        payment.calledInTransaction.clear();
        return orderId;
    }

    private String statusOf(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    private double count(String meter) {
        return meterRegistry.counter(meter).count();
    }

    @Test
    @DisplayName("만료 스케줄러는 시간이 지난 HOLD만 EXPIRED로 바꾸고 좌석을 잠근 뒤 돌려준다")
    void expiresDueHolds() {
        UUID seat = UUID.randomUUID();
        UUID expired = hold(Duration.ofMinutes(1), seat);
        UUID alive = hold(Duration.ofMinutes(30), UUID.randomUUID());
        UUID paying = hold(Duration.ofMinutes(10), UUID.randomUUID());
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
        assertThat(count("reservation.expired")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 만료되고, 실패한 건은 다음 실행의 후보로 남는다")
    void oneFailureDoesNotStopOthers() {
        UUID failingSeat = UUID.randomUUID();
        UUID failing = hold(Duration.ofMinutes(1), failingSeat);
        UUID other = hold(Duration.ofMinutes(2), UUID.randomUUID());
        seats.failOnLock(failingSeat);
        clock.advance(Duration.ofMinutes(5));

        assertThat(holdExpiryService.expireDue(10, BUDGET)).isEqualTo(1);

        assertThat(statusOf(failing)).isEqualTo("HOLD");
        assertThat(statusOf(other)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("만료 스케줄러 두 개가 동시에 돌아도 예약마다 한 번만 만료하고 좌석도 한 번만 돌려준다")
    void concurrentExpiry() throws Exception {
        for (int i = 0; i < 5; i++) {
            hold(Duration.ofMinutes(1), UUID.randomUUID());
        }
        clock.advance(Duration.ofMinutes(5));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return holdExpiryService.expireDue(10, BUDGET);
            }));
        }
        start.countDown();
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
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());
        paymentInProgress(reservationId);
        clock.advance(Duration.ofSeconds(30));

        assertThat(reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET))
                .isZero();

        assertThat(payment.lookedUpOrderIds).isEmpty();
    }

    @Test
    @DisplayName("payment가 승인 요청을 받지 못했으면 같은 orderId와 인증값으로 다시 보내고 결과를 반영한다")
    void resendsWhenNotReceived() {
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());
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
        UUID succeeded = hold(Duration.ofMinutes(10), UUID.randomUUID());
        UUID failed = hold(Duration.ofMinutes(10), UUID.randomUUID());
        UUID inProgress = hold(Duration.ofMinutes(10), UUID.randomUUID());
        UUID unknown = hold(Duration.ofMinutes(10), UUID.randomUUID());
        payment.lookupReturns(paymentInProgress(succeeded), PaymentLookup.of(Status.SUCCEEDED));
        payment.lookupReturns(paymentInProgress(failed), PaymentLookup.failed("DECLINED"));
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
    @DisplayName("상한 시간을 넘겨 결제 중인 예약은 알림을 세고 상태는 바꾸지 않는다")
    void countsStuckPayments() {
        UUID reservationId = hold(Duration.ofMinutes(10), UUID.randomUUID());
        paymentInProgress(reservationId);
        clock.advance(Duration.ofMinutes(31));
        double before = count("reservation.payment.stuck");

        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);

        assertThat(count("reservation.payment.stuck")).isEqualTo(before + 1);
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("한 번 실행은 시간 예산을 넘기면 멈추고 남은 건을 다음 실행에 넘긴다")
    void stopsAtTimeBudget() {
        for (int i = 0; i < 3; i++) {
            paymentInProgress(hold(Duration.ofMinutes(10), UUID.randomUUID()));
        }
        clock.advance(Duration.ofMinutes(2));
        payment.lookupDelay(Duration.ofMillis(300));

        reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, Duration.ofMillis(450));

        assertThat(payment.lookedUpOrderIds).hasSize(2);
    }
}
