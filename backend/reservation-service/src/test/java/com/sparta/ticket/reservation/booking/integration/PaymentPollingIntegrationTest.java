package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentBacklogService;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 결제 중인 사용자가 결과를 기다리며 부르는 조회. 승인을 메시지로 요청하면 모든 사용자가 결과를 폴링하므로,
 * 조회 한 번의 비용과 횟수를 서버가 줄일 수 있어야 한다.
 */
class PaymentPollingIntegrationTest extends BookingIntegrationTest {

    private static final String DETAIL = "/api/reservations/{id}";
    private static final String STATUS = "/api/reservations/{id}/status";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private BookingMetrics metrics;

    @Autowired
    private PaymentBacklogService backlogService;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    private Long hold() {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));
    }

    private MockHttpServletRequestBuilder as(Long userId, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER");
    }

    private ResultActions pollStatus(Long reservationId) throws Exception {
        return mockMvc.perform(as(user.userId(), get(STATUS, reservationId)));
    }

    private ResultActions detail(Long reservationId) throws Exception {
        return mockMvc.perform(as(user.userId(), get(DETAIL, reservationId)));
    }

    private void confirm(Long reservationId) {
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
    }

    /** 승인을 메시지로 요청해 결과를 기다리는 예약. */
    private Long paymentInProgress() {
        confirmSettings.useMessage();
        Long reservationId = hold();
        confirm(reservationId);
        return reservationId;
    }

    @Test
    @DisplayName("상태 조회는 상태만 주고 티켓은 읽지 않으며, 응답을 캐시하지 않는다")
    void statusReturnsOnlyStatus() throws Exception {
        Long reservationId = hold();

        pollStatus(reservationId)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId))
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.tickets").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("결제 중이면 상태 조회와 단건 조회 모두 다음 조회까지 기다릴 초를 Retry-After로 준다")
    void retryAfterWhileInProgress() throws Exception {
        Long reservationId = paymentInProgress();

        pollStatus(reservationId)
                .andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"))
                .andExpect(header().string("Retry-After", "1"));
        detail(reservationId)
                .andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"))
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    @DisplayName("결제 중 예약이 많을수록 조회 간격을 두 배씩 늘리고 상한에서 멈춘다")
    void retryAfterGrowsWithBacklog() throws Exception {
        Long reservationId = paymentInProgress();

        metrics.paymentBacklog(2_000, 0);
        pollStatus(reservationId).andExpect(header().string("Retry-After", "4"));

        metrics.paymentBacklog(10_000, 0);
        pollStatus(reservationId).andExpect(header().string("Retry-After", "5"));
    }

    @Test
    @DisplayName("결제 중 집계는 지금 결제 중인 예약 수를 세어 폴링 간격의 기준을 갱신한다")
    void backlogRefreshCountsInProgress() {
        paymentInProgress();
        paymentInProgress();
        hold();
        metrics.paymentBacklog(5_000, 0);

        assertThat(backlogService.refresh()).isEqualTo(2);

        assertThat(metrics.paymentsInProgressCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("결과가 정해지면 Retry-After를 주지 않아 클라이언트가 조회를 멈춘다")
    void noRetryAfterOnceSettled() throws Exception {
        Long reservationId = hold();
        payment.confirmReturns(ConfirmOutcome.succeeded());
        confirm(reservationId);

        pollStatus(reservationId)
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("상태 조회도 본인만 볼 수 있고, 없는 예약은 404다")
    void statusIsOwnerOnly() throws Exception {
        Long reservationId = hold();

        mockMvc.perform(as(TestIds.next(), get(STATUS, reservationId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        pollStatus(Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }
}
