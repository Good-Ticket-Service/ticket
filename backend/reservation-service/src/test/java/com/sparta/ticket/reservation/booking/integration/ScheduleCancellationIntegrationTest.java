package com.sparta.ticket.reservation.booking.integration;

import static com.sparta.ticket.reservation.booking.support.BookingTestConfig.START_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.cancel.CancelReconcileService;
import com.sparta.ticket.reservation.booking.application.cancel.ReservationCancelService;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResultApplier;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundReason;
import com.sparta.ticket.reservation.booking.application.schedulecancel.ScheduleCanceled;
import com.sparta.ticket.reservation.booking.application.schedulecancel.ScheduleCancellationReceiver;
import com.sparta.ticket.reservation.booking.application.schedulecancel.ScheduleCancellationService;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.infrastructure.seat.SeatFacadeAdapter;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** 회차 취소 기록을 받은 뒤 예약 정리 스케줄러가 상태별로 끝까지 처리하는지, 실제 좌석·쿠폰 행까지 본다. */
class ScheduleCancellationIntegrationTest extends BookingIntegrationTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(1);
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final int BATCH = 100;
    private static final Duration NO_WAIT = Duration.ZERO;
    private static final LocalDateTime SCHEDULE_START_AT = START_TIME.plusHours(2);

    @Autowired
    private ScheduleCancellationReceiver receiver;

    @Autowired
    private ScheduleCancellationService cancellationService;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private ReservationCancelService cancelService;

    @Autowired
    private CancelReconcileService cancelReconcileService;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private final Long scheduleId = TestIds.next();
    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        seats.useReal(seatFacadeAdapter);
    }

    @AfterEach
    void tearDown() {
        seats.reset();
    }

    private static UserContext newUser() {
        return new UserContext(TestIds.next(), "hong01", Role.USER);
    }

    private Long seat(Long schedule) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,
                    section, row_label, seat_number, is_sellable, config_version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 1, 'VIP', 10000, 'A', '1', ?, TRUE, 1, ?, 'test', ?, 'test')
                RETURNING id
                """, Long.class, schedule, nextVenueSeatId, nextVenueSeatId++, START_TIME, START_TIME);
    }

    private Long hold(UserContext user, Long schedule, Long seatId) {
        Long reservationId = transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.startingAt(schedule, SCHEDULE_START_AT),
                TestSeats.priced(seatId),
                clock.now().plusMinutes(10)));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                reservationId,
                clock.now().plusMinutes(10),
                seatId);
        return reservationId;
    }

    private String checkout(UserContext user, Long reservationId) {
        return paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
    }

    private Long confirmed(UserContext user, Long schedule, Long seatId) {
        Long reservationId = hold(user, schedule, seatId);
        assertThat(paymentService
                        .confirm(user, reservationId, new ConfirmCommand(checkout(user, reservationId), "pk-1"))
                        .status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        return reservationId;
    }

    /** 승인 결과를 모르는 결제 중 예약을 만든다. */
    private Long paymentInProgress(UserContext user, Long seatId) {
        Long reservationId = hold(user, scheduleId, seatId);
        payment.confirmReturns(ConfirmOutcome.unknown());
        paymentService.confirm(user, reservationId, new ConfirmCommand(checkout(user, reservationId), "pk-1"));
        payment.confirmReturns(ConfirmOutcome.succeeded());
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        return reservationId;
    }

    private void receive(Long schedule) {
        receiver.receive(new ScheduleCanceled(UUID.randomUUID().toString(), schedule, clock.now()));
    }

    private void cleanUp() {
        cancellationService.cleanUp(BATCH, NO_WAIT, BUDGET);
    }

    private String seatStatus(Long seatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM p_schedule_seats WHERE id = ?", String.class, seatId);
    }

    private String orderIdOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    private String recordStatus(Long schedule) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_schedule_cancellations WHERE schedule_id = ?", String.class, schedule);
    }

    private static void assertRejected(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_STATUS_TRANSITION));
    }

    @Test
    @DisplayName("확정 예약은 회차가 이미 시작했어도 SCHEDULE_CANCELED로 환불을 요청하고 CANCELED가 되며 좌석과 쿠폰이 돌아온다")
    void refundsConfirmedReservation() {
        UserContext user = newUser();
        Long seatId = seat(scheduleId);
        Long coupon = coupons.issue(user.userId(), 3_000);
        Long reservationId = hold(user, scheduleId, seatId);
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(coupon, "TOSS"))
                .orderId();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-1"));
        clock.set(SCHEDULE_START_AT.plusMinutes(1));
        receive(scheduleId);

        cleanUp();

        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(payment.refundedOrderIds).containsExactly(orderId);
        assertThat(payment.refundReasons).containsExactly(RefundReason.SCHEDULE_CANCELED);
        assertThat(payment.calledInTransaction).as("payment 호출은 트랜잭션 밖이다").containsOnly(false);
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("선점 예약은 환불 없이 CANCELED가 되고, 지난 준비의 orderId를 지우며 좌석과 잡은 쿠폰이 돌아온다")
    void cancelsHoldWithoutRefund() {
        UserContext user = newUser();
        Long seatId = seat(scheduleId);
        Long coupon = coupons.issue(user.userId(), 3_000);
        Long reservationId = hold(user, scheduleId, seatId);
        paymentService.checkout(user, reservationId, new CheckoutCommand(coupon, "TOSS"));
        receive(scheduleId);

        cleanUp();

        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(orderIdOf(reservationId)).as("확정된 적 있는 취소로 읽히지 않게 지운다").isNull();
        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("환불 결과를 모르면 취소 중으로 남고, 취소 확인이 같은 사유로 다시 요청해 끝낸 뒤 기록을 완료한다")
    void reconcileKeepsScheduleReason() {
        Long reservationId = confirmed(newUser(), scheduleId, seat(scheduleId));
        receive(scheduleId);
        payment.refundTimesOut();

        cleanUp();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELING");
        assertThat(recordStatus(scheduleId)).isEqualTo("RECEIVED");

        payment.refundReturns(RefundOutcome.accepted());
        clock.advance(THRESHOLD.plusSeconds(1));
        cancelReconcileService.reconcile(THRESHOLD, BATCH, BUDGET);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(payment.refundReasons)
                .containsExactly(RefundReason.SCHEDULE_CANCELED, RefundReason.SCHEDULE_CANCELED);

        cleanUp();
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("사용자가 먼저 취소한 예약은 환불을 또 요청하지 않고, 다시 요청할 때도 USER_CANCEL을 지킨다")
    void userCancelKeepsItsReason() {
        UserContext user = newUser();
        Long reservationId = confirmed(user, scheduleId, seat(scheduleId));
        payment.refundTimesOut();
        cancelService.cancel(user, reservationId);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELING");
        receive(scheduleId);

        cleanUp();
        assertThat(payment.refundReasons).as("정리는 취소 중인 예약을 건드리지 않는다").containsExactly(RefundReason.USER_CANCEL);

        payment.refundReturns(RefundOutcome.accepted());
        clock.advance(THRESHOLD.plusSeconds(1));
        cancelReconcileService.reconcile(THRESHOLD, BATCH, BUDGET);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(payment.refundReasons).containsExactly(RefundReason.USER_CANCEL, RefundReason.USER_CANCEL);
    }

    @Test
    @DisplayName("결제 중 예약은 결과를 기다리고, 성공으로 확정되면 다음 차례에 환불한다")
    void waitsForPaymentThenRefundsSuccess() {
        Long reservationId = paymentInProgress(newUser(), seat(scheduleId));
        String orderId = orderIdOf(reservationId);
        receive(scheduleId);

        cleanUp();
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(recordStatus(scheduleId)).isEqualTo("RECEIVED");

        paymentResultApplier.apply(reservationId, orderId, ConfirmOutcome.succeeded());
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");

        cleanUp();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(payment.refundedOrderIds).containsExactly(orderId);
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("결제 중 예약이 실패해 선점으로 돌아오면 다음 차례에 환불 없이 CANCELED가 된다")
    void waitsForPaymentThenClosesFailure() {
        Long seatId = seat(scheduleId);
        Long reservationId = paymentInProgress(newUser(), seatId);
        receive(scheduleId);
        cleanUp();

        paymentResultApplier.apply(reservationId, orderIdOf(reservationId), ConfirmOutcome.failed());
        assertThat(statusOf(reservationId)).isEqualTo("HOLD");

        cleanUp();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("취소 기록이 있으면 선점 예약의 승인은 결제를 부르지 않고 409로 거절하고 선점은 그대로다")
    void rejectsConfirmAfterCancellation() {
        UserContext user = newUser();
        Long reservationId = hold(user, scheduleId, seat(scheduleId));
        String orderId = checkout(user, reservationId);
        receive(scheduleId);

        assertRejected(() -> paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-1")));

        assertThat(payment.confirmedOrderIds).isEmpty();
        assertThat(statusOf(reservationId)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("취소 기록이 있으면 결제 준비는 쿠폰을 잡지 않고 결제도 부르지 않고 409로 거절한다")
    void rejectsCheckoutAfterCancellation() {
        UserContext user = newUser();
        Long coupon = coupons.issue(user.userId(), 3_000);
        Long reservationId = hold(user, scheduleId, seat(scheduleId));
        receive(scheduleId);

        assertRejected(() -> paymentService.checkout(user, reservationId, new CheckoutCommand(coupon, "TOSS")));

        assertThat(payment.prepared).isEmpty();
        assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("기록을 완료한 뒤 늦게 도착한 선점도 결제할 수 없다")
    void lateHoldAfterCompletionCannotPay() {
        receive(scheduleId);
        cleanUp();
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");

        UserContext user = newUser();
        Long reservationId = hold(user, scheduleId, seat(scheduleId));

        assertRejected(() -> paymentService.checkout(user, reservationId, new CheckoutCommand(null, "TOSS")));
        assertThat(payment.prepared).isEmpty();
    }

    @Test
    @DisplayName("기록을 받은 직후에는 예약이 없어도 완료하지 않고, 커밋 전이던 결제 시작이 끝날 시간이 지난 뒤 완료한다")
    void waitsBeforeCompleting() {
        Duration completeAfter = Duration.ofMinutes(1);
        receive(scheduleId);

        cancellationService.cleanUp(BATCH, completeAfter, BUDGET);
        assertThat(recordStatus(scheduleId)).isEqualTo("RECEIVED");

        clock.advance(completeAfter);
        cancellationService.cleanUp(BATCH, completeAfter, BUDGET);
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("기록을 완료한 회차에 확정 예약이 남으면 환불이 빠졌다는 뜻이라 건수를 지표로 내보낸다")
    void reportsConfirmedAfterCompletion() {
        Long reservationId = confirmed(newUser(), scheduleId, seat(scheduleId));
        receive(scheduleId);

        cancellationService.cleanUp(BATCH, NO_WAIT, Duration.ofNanos(-1));
        assertThat(confirmedAfterCompleted())
                .as("끝나지 않은 기록의 확정 예약은 정리가 처리할 일이다")
                .isZero();

        jdbcTemplate.update(
                "UPDATE p_schedule_cancellations SET status = 'COMPLETED', completed_at = ? WHERE schedule_id = ?",
                clock.now(),
                scheduleId);
        cancellationService.cleanUp(BATCH, NO_WAIT, BUDGET);

        assertThat(confirmedAfterCompleted()).isEqualTo(1);
        assertThat(statusOf(reservationId)).as("경보만 내고 자동으로 환불하지 않는다").isEqualTo("CONFIRMED");
    }

    private double confirmedAfterCompleted() {
        return meterRegistry
                .get("reservation.schedule_cancel.confirmed_after_completed")
                .gauge()
                .value();
    }

    @Test
    @DisplayName("같은 회차의 메시지가 완료 뒤 다시 와도 기록은 완료 그대로다")
    void redeliveryKeepsCompletedRecord() {
        receive(scheduleId);
        cleanUp();

        receive(scheduleId);

        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("다른 회차의 예약은 건드리지 않고 결제도 그대로 할 수 있다")
    void leavesOtherSchedulesAlone() {
        Long otherSchedule = TestIds.next();
        UserContext user = newUser();
        Long held = hold(user, otherSchedule, seat(otherSchedule));
        Long sold = confirmed(newUser(), otherSchedule, seat(otherSchedule));
        receive(scheduleId);

        cleanUp();

        assertThat(statusOf(held)).isEqualTo("HOLD");
        assertThat(statusOf(sold)).isEqualTo("CONFIRMED");
        assertThat(payment.refundedOrderIds).isEmpty();
        paymentService.confirm(user, held, new ConfirmCommand(checkout(user, held), "pk-1"));
        assertThat(statusOf(held)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("한 번에 묶음 크기만큼만 처리하고, 남은 예약은 다음 실행에서 끝낸 뒤 기록을 완료한다")
    void processesInBatches() {
        List<Long> reservations = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reservations.add(hold(newUser(), scheduleId, seat(scheduleId)));
        }
        receive(scheduleId);

        cancellationService.cleanUp(2, NO_WAIT, BUDGET);
        assertThat(reservations.stream().map(this::statusOf).filter("CANCELED"::equals))
                .hasSize(2);
        assertThat(recordStatus(scheduleId)).isEqualTo("RECEIVED");

        cancellationService.cleanUp(2, NO_WAIT, BUDGET);
        assertThat(reservations).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("CANCELED"));
        assertThat(recordStatus(scheduleId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("시간 예산을 다 쓰면 다음 예약을 시작하지 않고 다음 실행에 넘긴다")
    void stopsAtTimeBudget() {
        Long reservationId = confirmed(newUser(), scheduleId, seat(scheduleId));
        receive(scheduleId);

        cancellationService.cleanUp(BATCH, NO_WAIT, Duration.ofNanos(-1));

        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(recordStatus(scheduleId)).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("인스턴스 두 대가 같은 회차를 동시에 정리해도 예약마다 환불은 한 번이다")
    void twoInstancesRefundOnce() throws Exception {
        List<Long> reservations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            reservations.add(confirmed(newUser(), scheduleId, seat(scheduleId)));
        }
        receive(scheduleId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return cancellationService.cleanUp(BATCH, NO_WAIT, BUDGET);
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(reservations).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("CANCELED"));
        assertThat(payment.refundedOrderIds).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("사용자 취소와 회차 취소 정리가 겹쳐도 환불은 한 번이고 예약은 CANCELED로 끝난다")
    void userCancelAndCleanUpRefundOnce() throws Exception {
        UserContext user = newUser();
        Long reservationId = confirmed(user, scheduleId, seat(scheduleId));
        receive(scheduleId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> userCancel = pool.submit(() -> {
                start.await();
                return cancelService.cancel(user, reservationId);
            });
            Future<?> cleanUp = pool.submit(() -> {
                start.await();
                return cancellationService.cleanUp(BATCH, NO_WAIT, BUDGET);
            });
            start.countDown();
            userCancel.get(30, TimeUnit.SECONDS);
            cleanUp.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(payment.refundedOrderIds).hasSize(1);
    }

    @Test
    @DisplayName("Kafka 메시지 한 건을 받으면 확정 예약이 환불을 거쳐 CANCELED까지 간다")
    void messageDrivesReservationToCanceled() throws Exception {
        Long seatId = seat(scheduleId);
        Long reservationId = confirmed(newUser(), scheduleId, seatId);

        kafkaTemplate
                .send("schedule.canceled", String.valueOf(scheduleId), """
                        {"eventId":"%s","schemaVersion":1,"scheduleId":%d,"canceledAt":"2026-11-01T10:00:00+09:00"}""".formatted(UUID.randomUUID(), scheduleId))
                .get();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            cleanUp();
            assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        });

        assertThat(payment.refundReasons).containsExactly(RefundReason.SCHEDULE_CANCELED);
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
    }
}
