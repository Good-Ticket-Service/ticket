package com.sparta.ticket.reservation.booking.integration;

import static com.sparta.ticket.reservation.booking.support.BookingTestConfig.START_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.cancel.CancelReconcileService;
import com.sparta.ticket.reservation.booking.application.cancel.CancelTransactions;
import com.sparta.ticket.reservation.booking.application.cancel.ReservationCancelService;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResultApplier;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
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
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 확정한 예약의 취소가 환불 요청을 거쳐 실제 좌석 행까지 돌려주는지, 결과를 모를 때 스케줄러가 마무리하는지 본다. */
class ReservationCancelIntegrationTest extends BookingIntegrationTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(1);
    private static final LocalDateTime SCHEDULE_START_AT = START_TIME.plusHours(2);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private ReservationCancelService cancelService;

    @Autowired
    private CancelReconcileService cancelReconcileService;

    @Autowired
    private PaymentReconcileService paymentReconcileService;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private CancelTransactions cancelTransactions;

    @Autowired
    private SeatFacadeAdapter seatFacadeAdapter;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);
    private final Long scheduleId = TestIds.next();
    private long nextVenueSeatId = 1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        seats.useReal(seatFacadeAdapter);
    }

    @AfterEach
    void tearDown() {
        // 컨텍스트를 다른 테스트와 함께 쓰므로 실제 좌석 연결을 풀어 둔다.
        seats.reset();
    }

    private Long seat() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,
                    section, row_label, seat_number, is_sellable, config_version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 1, 'VIP', 10000, 'A', '1', ?, TRUE, 1, ?, 'test', ?, 'test')
                RETURNING id
                """, Long.class, scheduleId, nextVenueSeatId, nextVenueSeatId++, START_TIME, START_TIME);
    }

    /** seat의 선점처럼 예약을 만들고 좌석을 그 예약의 HOLD로 둔다. */
    private Long hold(Long seatId) {
        Long reservationId = transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.startingAt(scheduleId, SCHEDULE_START_AT),
                TestSeats.priced(seatId),
                START_TIME.plusMinutes(10)));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                reservationId,
                START_TIME.plusMinutes(10),
                seatId);
        return reservationId;
    }

    /** 선점하고 결제까지 끝내 CONFIRMED로 만든다. 좌석은 SOLD다. */
    private Long confirmed(Long seatId) {
        Long reservationId = hold(seatId);
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        assertThat(paymentService
                        .confirm(user, reservationId, new ConfirmCommand(orderId, "pk-1"))
                        .status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        return reservationId;
    }

    private ResultActions cancel(Long reservationId, Long userId) throws Exception {
        return mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId)
                .header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON));
    }

    private String orderIdOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    private Map<String, Object> seatRow(Long seatId) {
        return jdbcTemplate.queryForMap("SELECT status, reservation_id FROM p_schedule_seats WHERE id = ?", seatId);
    }

    private int activeSeats() {
        Integer count =
                transactionTemplate.execute(status -> bookingFacade.countActiveSeats(user.userId(), scheduleId));
        return count;
    }

    private int reconcileCancels() {
        return cancelReconcileService.reconcile(THRESHOLD, 10, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("회차 시작 전에 취소하면 확정에 쓴 orderId로 환불을 한 번 요청하고, CANCELED가 되고 좌석이 다시 팔 수 있게 된다")
    void cancelsConfirmedReservation() throws Exception {
        Long seatId = seat();
        Long reservationId = confirmed(seatId);
        String orderId = orderIdOf(reservationId);
        double canceledBefore = count("reservation.canceled");

        cancel(reservationId, user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.orderId").value(orderId));

        assertThat(payment.refundedOrderIds).containsExactly(orderId);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
        assertThat(count("reservation.canceled") - canceledBefore).isEqualTo(1);
        assertThat(payment.calledInTransaction).as("payment 호출은 트랜잭션 밖이다").containsOnly(false);
    }

    @Test
    @DisplayName("확정 때 사용한 쿠폰은 취소가 확정될 때 돌아온다. 취소 중에는 사용한 그대로다")
    void cancelRestoresUsedCoupon() throws Exception {
        Long seatId = seat();
        Long coupon = coupons.issue(user.userId(), 3_000);
        Long reservationId = hold(seatId);
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(coupon, "TOSS"))
                .orderId();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-1"));
        assertThat(coupons.statusOf(coupon)).isEqualTo("USED");
        payment.refundTimesOut();

        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));
        assertThat(coupons.statusOf(coupon)).as("환불 요청을 받았는지 모르면 돌려주지 않는다").isEqualTo("USED");

        payment.refundReturns(RefundOutcome.accepted());
        clock.advance(THRESHOLD.plusSeconds(1));
        reconcileCancels();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        assertThat(coupons.reservationOf(coupon)).isNull();
    }

    @Test
    @DisplayName("회차 시작 시각과 같거나 지났으면 409 NOT_CANCELLABLE이고 환불을 요청하지 않는다. 1초 전에는 취소된다")
    void rejectsAtOrAfterScheduleStart() throws Exception {
        Long reservationId = confirmed(seat());

        clock.set(SCHEDULE_START_AT);
        cancel(reservationId, user.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
        clock.set(SCHEDULE_START_AT.plusMinutes(1));
        cancel(reservationId, user.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");

        clock.set(SCHEDULE_START_AT.minusSeconds(1));
        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    @DisplayName("확정 전·끝난 예약은 409 INVALID_STATUS_TRANSITION, 남의 예약은 403, 없는 예약은 404이고 환불을 요청하지 않는다")
    void rejectsWrongReservations() throws Exception {
        Long held = hold(seat());
        cancel(held, user.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        // 끝난 선점도 같다. 활성 예약은 회원·회차당 하나라 다음 예약을 만들기 전에 끝낸다
        jdbcTemplate.update("UPDATE p_reservations SET status = 'EXPIRED' WHERE reservation_id = ?", held);
        cancel(held, user.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        Long confirmed = confirmed(seat());
        cancel(confirmed, TestIds.next())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        cancel(TestIds.next(), user.userId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        assertThat(payment.refundedOrderIds).isEmpty();
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("환불 요청의 결과를 모르면 CANCELING으로 답하고 좌석은 SOLD 그대로다. 스케줄러가 같은 orderId로 다시 요청해 끝낸다")
    void unknownRefundIsFinishedByScheduler() throws Exception {
        Long seatId = seat();
        Long reservationId = confirmed(seatId);
        String orderId = orderIdOf(reservationId);
        payment.refundTimesOut();

        cancel(reservationId, user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"));
        assertThat(seatRow(seatId)).containsEntry("status", "SOLD").containsEntry("reservation_id", reservationId);

        payment.refundReturns(RefundOutcome.accepted());
        assertThat(reconcileCancels()).as("기준 시간 전에는 다시 요청하지 않는다").isZero();
        // 회차가 시작된 뒤에도 끝낸다. 기한은 취소를 받을 때만 본다
        clock.set(SCHEDULE_START_AT.plusHours(1));
        assertThat(reconcileCancels()).isEqualTo(1);

        assertThat(payment.refundedOrderIds).containsExactly(orderId, orderId);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE").containsEntry("reservation_id", null);
        assertThat(reconcileCancels()).as("끝난 예약은 다시 고르지 않는다").isZero();
    }

    @Test
    @DisplayName("payment가 orderId를 모르면 CANCELING으로 두고 알린다. 다시 요청해도 같으면 계속 CANCELING이다")
    void unknownOrderKeepsCanceling() throws Exception {
        Long seatId = seat();
        Long reservationId = confirmed(seatId);
        payment.refundReturns(RefundOutcome.unknownOrder());
        double before = count("reservation.cancel.unknown_order");

        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));
        clock.advance(THRESHOLD.plusSeconds(1));
        reconcileCancels();

        assertThat(statusOf(reservationId)).isEqualTo("CANCELING");
        assertThat(count("reservation.cancel.unknown_order") - before).isEqualTo(2);
        assertThat(seatRow(seatId)).containsEntry("status", "SOLD");
    }

    @Test
    @DisplayName("다시 요청해도 끝나지 않는 예약이 한 묶음을 채워도, 다음 실행은 아직 확인하지 않은 예약부터 고른다")
    void doesNotStarveOnUnfinishedCancels() throws Exception {
        Long stuck = confirmed(seat());
        payment.refundReturns(RefundOutcome.unknownOrder());
        cancel(stuck, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));

        UserContext other = new UserContext(TestIds.next(), "kim01", Role.USER);
        Long otherSeat = seat();
        Long waiting = transactionTemplate.execute(status -> bookingFacade.hold(
                other.userId(),
                TestHoldTargets.startingAt(scheduleId, SCHEDULE_START_AT),
                TestSeats.priced(otherSeat),
                START_TIME.plusMinutes(10)));
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = ?, hold_expires_at = ? WHERE id = ?",
                waiting,
                START_TIME.plusMinutes(10),
                otherSeat);
        String orderId = paymentService
                .checkout(other, waiting, new CheckoutCommand(null, "TOSS"))
                .orderId();
        paymentService.confirm(other, waiting, new ConfirmCommand(orderId, "pk-1"));
        clock.advance(Duration.ofSeconds(1));
        payment.refundTimesOut();
        cancelService.cancel(other, waiting);

        payment.refundAnswers(() -> payment.refundedOrderIds
                        .get(payment.refundedOrderIds.size() - 1)
                        .equals(orderId)
                ? RefundOutcome.accepted()
                : RefundOutcome.unknownOrder());
        clock.advance(THRESHOLD.plusSeconds(1));
        cancelReconcileService.reconcile(THRESHOLD, 1, Duration.ofSeconds(5));
        cancelReconcileService.reconcile(THRESHOLD, 1, Duration.ofSeconds(5));

        assertThat(statusOf(stuck)).isEqualTo("CANCELING");
        assertThat(statusOf(waiting)).as("먼저 취소 중이 된 예약에 막히지 않는다").isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("이미 취소 중이거나 취소된 예약을 다시 취소하면 환불을 다시 요청하지 않고 지금 상태를 준다. 기한도 보지 않는다")
    void repeatedCancelReturnsCurrentState() throws Exception {
        Long canceling = confirmed(seat());
        payment.refundTimesOut();
        cancel(canceling, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));
        payment.refundReturns(RefundOutcome.accepted());

        cancel(canceling, user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"));
        assertThat(payment.refundedOrderIds).hasSize(1);

        clock.set(SCHEDULE_START_AT);
        cancel(canceling, user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"));
        assertThat(payment.refundedOrderIds).hasSize(1);
    }

    @Test
    @DisplayName("이미 취소된 예약을 다시 취소하면 200 CANCELED이고 환불을 다시 요청하지 않는다")
    void repeatedCancelAfterCanceled() throws Exception {
        Long reservationId = confirmed(seat());
        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELED"));

        cancel(reservationId, user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
        assertThat(payment.refundedOrderIds).hasSize(1);
    }

    @Test
    @DisplayName("같은 예약에 취소가 동시에 와도 환불 요청은 한 번이고, 모두 오류 없이 지금 상태를 받는다")
    void concurrentCancelRefundsOnce() throws Exception {
        Long seatId = seat();
        Long reservationId = confirmed(seatId);
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<ReservationStatus>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cancelService.cancel(user, reservationId).status();
                }));
            }
            start.countDown();
            for (Future<ReservationStatus> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS))
                        .isIn(ReservationStatus.CANCELING, ReservationStatus.CANCELED);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(payment.refundedOrderIds).hasSize(1);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("환불은 받았는데 취소 확정이 실패하면 CANCELING으로 답하고, 스케줄러가 다시 요청해 확정한다")
    void finishFailureIsRetriedByScheduler() throws Exception {
        Long seatId = seat();
        Long reservationId = confirmed(seatId);
        seats.failOnLock(seatId);

        cancel(reservationId, user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"));
        assertThat(seatRow(seatId)).containsEntry("status", "SOLD");

        seats.reset();
        seats.useReal(seatFacadeAdapter);
        clock.advance(THRESHOLD.plusSeconds(1));
        assertThat(reconcileCancels()).isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
        assertThat(seatRow(seatId)).containsEntry("status", "AVAILABLE");
    }

    @Test
    @DisplayName("여러 인스턴스가 같은 취소를 같은 차례에 골라도 한쪽만 환불을 다시 요청한다")
    void onlyOneInstanceClaimsStaleCancel() throws Exception {
        Long reservationId = confirmed(seat());
        payment.refundTimesOut();
        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));
        clock.advance(THRESHOLD.plusSeconds(1));
        LocalDateTime staleBefore = clock.now().minus(THRESHOLD);

        assertThat(cancelTransactions.claimPendingCancel(reservationId, staleBefore))
                .isPresent();
        assertThat(cancelTransactions.claimPendingCancel(reservationId, staleBefore))
                .as("같은 차례의 다른 인스턴스")
                .isEmpty();
    }

    @Test
    @DisplayName("취소한 예약에 같은 결제 성공이 다시 와도 자동 환불 대상으로 세지 않는다. 환불은 취소가 이미 요청했다")
    void duplicateSuccessAfterCancelIsIgnored() throws Exception {
        Long reservationId = confirmed(seat());
        String orderId = orderIdOf(reservationId);
        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELED"));
        double before = lateSuccessCount();

        paymentResultApplier.apply(reservationId, orderId, ConfirmOutcome.succeeded());

        assertThat(lateSuccessCount() - before).isZero();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("결제 결과 확인 스케줄러는 취소 중인 예약을 고르지 않는다")
    void paymentReconcileIgnoresCanceling() throws Exception {
        Long reservationId = confirmed(seat());
        payment.refundTimesOut();
        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));

        clock.advance(Duration.ofHours(1));
        paymentReconcileService.reconcile(THRESHOLD, Duration.ofMinutes(30), 10, Duration.ofSeconds(5));

        assertThat(payment.lookedUpOrderIds).isEmpty();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELING");
    }

    @Test
    @DisplayName("취소 중인 예약도 1인 매수 제한에서 센다. 취소된 뒤에는 세지 않는다")
    void cancelingCountsAsActive() throws Exception {
        Long reservationId = confirmed(seat());
        payment.refundTimesOut();
        cancel(reservationId, user.userId()).andExpect(jsonPath("$.data.status").value("CANCELING"));

        assertThat(activeSeats()).isEqualTo(1);

        payment.refundReturns(RefundOutcome.accepted());
        clock.advance(THRESHOLD.plusSeconds(1));
        reconcileCancels();
        assertThat(activeSeats()).isZero();
    }

    @Test
    @DisplayName("선점 때 받은 회차 시작 시각과 공연명을 예약에 저장한다")
    void holdStoresScheduleSnapshot() {
        Long reservationId = hold(seat());

        assertThat(jdbcTemplate.queryForMap(
                        "SELECT schedule_start_at, performance_title FROM p_reservations WHERE reservation_id = ?",
                        reservationId))
                .containsEntry("schedule_start_at", java.sql.Timestamp.valueOf(SCHEDULE_START_AT))
                .containsEntry("performance_title", TestHoldTargets.PERFORMANCE_TITLE);
    }
}
