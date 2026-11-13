package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentBacklogService;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 메시지 승인에서 결제 결과가 계속 늦어지면 결제 준비를 잠시 받지 않는다.
 * 설정(ReservationIntegrationTest): 접수 후 5초 넘게 결과가 없으면 늦은 결제, 20건을 넘고 10초 창의 앞뒤 절반에서 모두 늘면 거절,
 * 5건 이하로 내려오면 다시 받음, 신호가 10초 넘게 안 오면 받음, 선점 만료 2분 안이면 초당 1건까지 통과, Retry-After는 5초에서 60초 사이.
 * 테스트는 결제 결과를 메시지로 반영하지 않으므로 결제가 끝나는 속도는 초당 0건이고, 그때는 초당 1건으로 본다.
 * 신호는 1초마다 도는 결제 중 집계(refresh)가 갱신하므로 테스트는 시계를 움직이며 refresh를 직접 부른다.
 */
class PaymentAdmissionIntegrationTest extends BookingIntegrationTest {

    private static final Duration LATE = Duration.ofSeconds(6);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private PaymentBacklogService backlogService;

    private final Long userId = TestIds.next();

    private Long hold(Duration holdFor) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId,
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(TestIds.next()),
                clock.now().plus(holdFor)));
    }

    private ResultActions checkout(Long reservationId) throws Exception {
        return mockMvc.perform(post("/api/reservations/{id}/payments/checkout", reservationId)
                .header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pgProvider\": \"TOSS\"}"));
    }

    /** 다른 사용자들의 결제 중 예약을, 접수한 지 늦은 결제 기준(5초)을 넘긴 시각으로 넣는다. */
    private void latePayments(int count) {
        paymentsAcceptedAt(clock.now().minus(LATE), count);
    }

    private void paymentsAcceptedAt(LocalDateTime acceptedAt, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO p_reservations (user_id, schedule_id, performance_id, seller_id, schedule_start_at,
                        performance_title, status, hold_expires_at, order_id, payment_amount, confirm_before,
                        created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, 1, 1, ?, '공연', 'PAYMENT_IN_PROGRESS', ?, ?, 1000, ?, ?, 'test', ?, 'test')
                    """,
                    TestIds.next(),
                    TestIds.next(),
                    acceptedAt.plusDays(1),
                    acceptedAt.plusMinutes(10),
                    "late-" + TestIds.next(),
                    acceptedAt.plusMinutes(5),
                    acceptedAt,
                    acceptedAt);
        }
    }

    /** 결제 중 예약을 앞에서부터 count건 끝낸다. */
    private void finishPayments(int count) {
        jdbcTemplate.update("""
                UPDATE p_reservations SET status = 'CONFIRMED'
                 WHERE reservation_id IN (SELECT reservation_id FROM p_reservations
                                           WHERE status = 'PAYMENT_IN_PROGRESS' ORDER BY reservation_id LIMIT ?)
                """, count);
    }

    private void refreshAfter(Duration elapsed) {
        clock.advance(elapsed);
        backlogService.refresh();
    }

    private void rejectTimes(Long reservationId, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            checkout(reservationId).andExpect(status().isServiceUnavailable());
        }
    }

    /** 늦은 결제가 0 → 25 → 50으로 10초 동안 계속 늘어난 상태를 만든다. */
    private void paymentsKeepGettingLate() {
        backlogService.refresh();
        latePayments(25);
        refreshAfter(Duration.ofSeconds(5));
        latePayments(25);
        refreshAfter(Duration.ofSeconds(5));
    }

    /** 브로커가 받지 못한 승인 요청 하나를 지금 시각으로 Outbox 맨 앞에 둔다. 이 테스트들은 발행기를 돌리지 않아 그대로 남는다. */
    private void confirmRequestWaiting() {
        jdbcTemplate.update(
                "INSERT INTO p_booking_outbox (topic, message_key, payload, created_at) VALUES (?, ?, ?, ?)",
                "payment.confirm.requested",
                "1",
                "{}",
                clock.now());
    }

    private Object orderIdOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM p_reservations WHERE reservation_id = ?", Object.class, reservationId);
    }

    @Test
    @DisplayName("결제 결과가 계속 늦어지면 결제 준비를 503과 Retry-After로 거절하고, 결제를 부르거나 주문을 기록하지 않는다")
    void rejectsCheckoutWhilePaymentsKeepGettingLate() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        double rejectedBefore = count("reservation.admission.rejected");
        paymentsKeepGettingLate();

        checkout(reservationId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        assertThat(payment.prepared).isEmpty();
        assertThat(orderIdOf(reservationId)).isNull();
        assertThat(statusOf(reservationId)).isEqualTo("HOLD");
        assertThat(count("reservation.admission.rejected") - rejectedBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("늦은 결제가 많아도 늘지 않으면(느리지만 따라감) 결제 준비를 받는다")
    void acceptsWhenLatePaymentsStopGrowing() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        backlogService.refresh();
        latePayments(50);
        refreshAfter(Duration.ofSeconds(1));
        refreshAfter(Duration.ofSeconds(4));
        refreshAfter(Duration.ofSeconds(5));

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("거절을 시작하면 늦은 결제가 다시 받는 기준(5건) 이하로 내려올 때까지 거절한다")
    void keepsRejectingUntilLatePaymentsFallBelowResumeThreshold() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        checkout(reservationId).andExpect(status().isServiceUnavailable());

        finishPayments(40);
        refreshAfter(Duration.ofSeconds(1));
        checkout(reservationId).andExpect(status().isServiceUnavailable());

        finishPayments(6);
        refreshAfter(Duration.ofSeconds(1));
        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("늦은 결제가 계속 늘어도 거절 기준(20건)을 넘지 않으면 받는다")
    void acceptsWhileLatePaymentsStayAtThreshold() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        backlogService.refresh();
        latePayments(10);
        refreshAfter(Duration.ofSeconds(5));
        latePayments(10);
        refreshAfter(Duration.ofSeconds(5));

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("결제 중 예약이 빠르게 늘어도 아직 늦지 않았으면 받는다")
    void acceptsWhilePaymentsInProgressAreNotLate() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        backlogService.refresh();
        clock.advance(Duration.ofSeconds(5));
        paymentsAcceptedAt(clock.now(), 30);
        backlogService.refresh();
        clock.advance(Duration.ofSeconds(5));
        paymentsAcceptedAt(clock.now(), 30);
        backlogService.refresh();

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("거절 중에 결제 중 예약이 시한을 넘기면 결과를 기다리는 결제가 아니므로 거절을 풀고 받는다")
    void resumesWhenLatePaymentsPassTheirDeadline() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        checkout(reservationId).andExpect(status().isServiceUnavailable());

        refreshAfter(Duration.ofMinutes(5));

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("환불 대상으로 표시한 결제 중 예약은 늦은 결제로 세지 않아 거절이 풀린다")
    void doesNotCountPaymentsMarkedForRefund() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        checkout(reservationId).andExpect(status().isServiceUnavailable());

        jdbcTemplate.update(
                "UPDATE p_reservations SET refund_required_at = ? WHERE status = 'PAYMENT_IN_PROGRESS'", clock.now());
        refreshAfter(Duration.ofSeconds(1));

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("거절을 풀 때는 한꺼번에 열지 않고 결제가 끝나는 속도만큼만 초마다 통과시킨다")
    void reopensGraduallyAfterShedding() throws Exception {
        confirmSettings.useMessage();
        Long first = hold(Duration.ofMinutes(10));
        Long second = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        finishPayments(50);
        refreshAfter(Duration.ofSeconds(1));

        checkout(first).andExpect(status().isOk());
        checkout(second)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));
    }

    @Test
    @DisplayName("조금씩 연 동안 막힌 결제 준비가 없으면 다음 단계에서 활짝 연다")
    void opensFullyWhenNothingWasHeldBack() throws Exception {
        confirmSettings.useMessage();
        Long first = hold(Duration.ofMinutes(10));
        Long second = hold(Duration.ofMinutes(10));
        Long third = hold(Duration.ofMinutes(10));
        Long fourth = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        finishPayments(50);
        refreshAfter(Duration.ofSeconds(1));
        checkout(first).andExpect(status().isOk());

        refreshAfter(Duration.ofSeconds(5));

        // 두 배(초당 2건)로만 늘렸다면 세 번째는 막힌다
        checkout(second).andExpect(status().isOk());
        checkout(third).andExpect(status().isOk());
        checkout(fourth).andExpect(status().isOk());
    }

    @Test
    @DisplayName("조금씩 여는 중에 늦은 결제가 다시 거절 기준을 넘으면 바로 닫는다")
    void closesAgainWhileReopening() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        finishPayments(50);
        refreshAfter(Duration.ofSeconds(1));

        latePayments(30);
        refreshAfter(Duration.ofSeconds(1));

        checkout(reservationId).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("결과 메시지를 잃어 결제 한 건만 오래 남아도 거절하지 않는다")
    void ignoresSingleLostPayment() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        backlogService.refresh();
        latePayments(1);
        refreshAfter(Duration.ofSeconds(5));
        refreshAfter(Duration.ofSeconds(5));

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("동기 승인에서는 늦은 결제가 늘어도 결제 준비를 받는다")
    void doesNotLimitSyncConfirm() throws Exception {
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("늦은 결제 신호가 멈춘 지 10초가 넘으면 막지 않고 받는다")
    void acceptsWhenSignalIsStale() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        double staleBefore = count("reservation.admission.signal.stale");

        clock.advance(Duration.ofSeconds(11));

        checkout(reservationId).andExpect(status().isOk());
        assertThat(count("reservation.admission.signal.stale") - staleBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("거절 중에도 선점 만료가 2분 안에 오는 결제 준비는 초당 정한 수까지 받는다")
    void letsHoldsAboutToExpireThroughUpToRate() throws Exception {
        confirmSettings.useMessage();
        Long first = hold(Duration.ofSeconds(100));
        Long second = hold(Duration.ofSeconds(100));
        paymentsKeepGettingLate();

        checkout(first).andExpect(status().isOk());
        checkout(second).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("거절이 몰리면 기다리는 사용자 수를 결제가 끝나는 속도로 나눈 만큼 Retry-After를 늘린다")
    void stretchesRetryAfterWhenManyAreWaiting() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        rejectTimes(reservationId, 4);

        refreshAfter(Duration.ofSeconds(1));

        // 초당 4건이 5초마다 다시 오므로 20명이 기다리고, 초당 1건씩 받으면 20초 뒤에 차례가 온다
        checkout(reservationId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "20"));
    }

    @Test
    @DisplayName("Retry-After를 늘린 뒤에는 늘린 간격으로 다시 오는 거절 수로 기다리는 사용자를 센다")
    void countsWaitingUsersWithStretchedRetryAfter() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        rejectTimes(reservationId, 4);
        refreshAfter(Duration.ofSeconds(1));

        rejectTimes(reservationId, 2);
        refreshAfter(Duration.ofSeconds(1));

        // 20초 간격으로 초당 2건이 오면 40명이 기다린다
        checkout(reservationId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "40"));
    }

    @Test
    @DisplayName("거절당한 사용자가 다시 오지 않으면 Retry-After를 가장 짧은 값으로 되돌린다")
    void shortensRetryAfterWhenNobodyIsWaiting() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        rejectTimes(reservationId, 4);
        refreshAfter(Duration.ofSeconds(1));

        refreshAfter(Duration.ofSeconds(1));

        checkout(reservationId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));
    }

    @Test
    @DisplayName("기다리는 사용자가 아무리 많아도 Retry-After는 최대(60초)를 넘지 않는다")
    void capsRetryAfter() throws Exception {
        confirmSettings.useMessage();
        Long reservationId = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        rejectTimes(reservationId, 20);

        refreshAfter(Duration.ofSeconds(1));

        checkout(reservationId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
    }

    @Test
    @DisplayName("조금씩 여는 중에는 기다리는 사용자 수를 그 단계에서 초마다 통과시키는 수로 나눠 Retry-After를 정한다")
    void stretchesRetryAfterByReopenRate() throws Exception {
        confirmSettings.useMessage();
        Long first = hold(Duration.ofMinutes(10));
        Long second = hold(Duration.ofMinutes(10));
        Long third = hold(Duration.ofMinutes(10));
        Long waiting = hold(Duration.ofMinutes(10));
        paymentsKeepGettingLate();
        finishPayments(50);
        refreshAfter(Duration.ofSeconds(1));
        refreshAfter(Duration.ofSeconds(4));
        checkout(first).andExpect(status().isOk());
        rejectTimes(waiting, 4);

        // 단계가 끝났고 막힌 요청이 있었으며 늦은 결제가 없으므로 초당 2건으로 늘린다
        refreshAfter(Duration.ofSeconds(1));
        checkout(second).andExpect(status().isOk());
        checkout(third).andExpect(status().isOk());

        // 초당 4건 × 5초 = 20명을 초당 2건씩 받으면 10초
        checkout(waiting)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"));
    }

    @Test
    @DisplayName("승인 요청이 나가지 못해 승인을 동기로 돌리는 동안에는 늦은 결제가 계속 늘어도 결제 준비를 받고, 요청이 나가면 다시 거절한다")
    void admitsCheckoutWhileConfirmsGoSync() throws Exception {
        confirmSettings.useMessage();
        Long first = hold(Duration.ofMinutes(10));
        Long second = hold(Duration.ofMinutes(10));
        confirmRequestWaiting();
        // 늦은 결제가 거절 기준을 넘는 10초 동안 맨 앞 승인 요청도 기준 시간을 넘겨 기다린다
        paymentsKeepGettingLate();

        checkout(first).andExpect(status().isOk());
        assertThat(payment.prepared).hasSize(1);

        // 브로커가 받아 발행기가 지운 것과 같다
        jdbcTemplate.update("DELETE FROM p_booking_outbox");
        refreshAfter(Duration.ofSeconds(1));

        checkout(second).andExpect(status().isServiceUnavailable()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        assertThat(payment.prepared).hasSize(1);
    }
}
