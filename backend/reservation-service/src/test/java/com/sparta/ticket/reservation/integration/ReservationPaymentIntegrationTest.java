package com.sparta.ticket.reservation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.application.BookingFacade;
import com.sparta.ticket.reservation.application.PaymentResultApplier;
import com.sparta.ticket.reservation.application.ReservationTransactions;
import com.sparta.ticket.reservation.application.ReservationTransactions.CheckoutTarget;
import com.sparta.ticket.reservation.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.support.FakePaymentPort;
import com.sparta.ticket.reservation.support.FakeSeatPort;
import com.sparta.ticket.reservation.support.MutableClock;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationPaymentIntegrationTest extends ReservationIntegrationTest {

    private static final String USERNAME = "hong01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationTransactions reservationTransactions;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

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

    private final UUID userId = UUID.randomUUID();
    private final UUID seatA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID seatB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_reservation_seats");
        jdbcTemplate.update("DELETE FROM p_reservations");
        clock.set(LocalDateTime.of(2026, 11, 1, 20, 0));
        payment.reset();
        seats.reset();
    }

    private UUID hold() {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId, UUID.randomUUID(), List.of(seatB, seatA), clock.now().plusMinutes(10)));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID user) {
        return request.header(UserHeaders.USER_ID, user.toString())
                .header(UserHeaders.USERNAME, USERNAME)
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions checkout(UUID reservationId, UUID user) throws Exception {
        return mockMvc.perform(as(post("/api/v1/reservations/{id}/payments/checkout", reservationId), user)
                .content("{\"pgProvider\": \"TOSS\"}"));
    }

    private String checkoutOrderId(UUID reservationId) throws Exception {
        String body = checkout(reservationId, userId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("orderId").asText();
    }

    private ResultActions confirm(UUID reservationId, String orderId, UUID user) throws Exception {
        return mockMvc.perform(as(post("/api/v1/reservations/{id}/payments/confirm", reservationId), user)
                .content(objectMapper.writeValueAsString(Map.of("orderId", orderId, "pgAuthToken", "pk-1"))));
    }

    private Map<String, Object> row(UUID reservationId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, order_id, payment_amount, pg_auth_token, updated_by FROM p_reservations"
                        + " WHERE reservation_id = ?",
                reservationId);
    }

    private double lateSuccessCount() {
        return meterRegistry.counter("reservation.payment.late_success").count();
    }

    @Test
    @DisplayName("결제 준비와 승인이 성공하면 CONFIRMED가 되고 좌석을 먼저 잠근 뒤 판매한다")
    void checkoutAndConfirm() throws Exception {
        UUID reservationId = hold();
        payment.discount(3_000L);

        String orderId = checkoutOrderId(reservationId);
        confirm(reservationId, orderId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentAmount").value(17_000));

        assertThat(payment.prepared.get(0).baseAmount()).isEqualTo(20_000L);
        assertThat(payment.prepared.get(0).expiresAt()).isEqualTo(clock.now().plusMinutes(10));
        assertThat(payment.confirmedAmounts).as("승인 금액은 준비 응답의 값이다").containsExactly(17_000L);
        assertThat(seats.locked).containsExactly(List.of(seatA, seatB));
        assertThat(seats.sold).containsExactly(List.of(seatA, seatB));
        assertThat(payment.calledInTransaction).as("payment 호출은 트랜잭션 밖이다").containsOnly(false);
        Map<String, Object> row = row(reservationId);
        assertThat(row.get("pg_auth_token")).isNull();
        assertThat(row.get("updated_by")).as("요청 경로의 전이는 요청한 사용자를 남긴다").isEqualTo(USERNAME);
    }

    @Test
    @DisplayName("결제가 거절되면 선점 시간이 남은 동안 HOLD로 돌아오고, 다시 준비하면 이전 orderId의 승인은 거절된다")
    void failureReturnsToHold() throws Exception {
        UUID reservationId = hold();
        String firstOrderId = checkoutOrderId(reservationId);
        payment.confirmReturns(ConfirmOutcome.failed("CARD_DECLINED"));

        confirm(reservationId, firstOrderId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLD"));

        String secondOrderId = checkoutOrderId(reservationId);
        assertThat(secondOrderId).isNotEqualTo(firstOrderId);
        confirm(reservationId, firstOrderId, userId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ID_MISMATCH"));
        assertThat(seats.released).isEmpty();
    }

    @Test
    @DisplayName("결제가 거절됐을 때 선점 시간이 지났으면 EXPIRED가 되고 좌석을 돌려준다")
    void failureAfterExpiry() throws Exception {
        UUID reservationId = hold();
        String orderId = checkoutOrderId(reservationId);
        payment.confirmAnswers(() -> {
            clock.advance(Duration.ofMinutes(11));
            return ConfirmOutcome.failed("EXPIRED");
        });

        confirm(reservationId, orderId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));

        assertThat(seats.released).containsExactly(List.of(seatA, seatB));
    }

    @Test
    @DisplayName("승인 결과를 모르면 PAYMENT_IN_PROGRESS로 응답하고 다시 보낼 인증값을 남긴다")
    void unknownOutcome() throws Exception {
        UUID reservationId = hold();
        String orderId = checkoutOrderId(reservationId);
        payment.confirmTimesOut();

        confirm(reservationId, orderId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"));

        assertThat(row(reservationId).get("pg_auth_token")).isEqualTo("pk-1");
        assertThat(payment.confirmedOrderIds).as("사용자 요청 경로에서는 다시 보내지 않는다").hasSize(1);
    }

    @Test
    @DisplayName("같은 승인이 다시 오면 payment를 다시 부르지 않고 지금 상태를 돌려준다")
    void duplicateConfirm() throws Exception {
        UUID reservationId = hold();
        String orderId = checkoutOrderId(reservationId);
        confirm(reservationId, orderId, userId).andExpect(status().isOk());

        confirm(reservationId, orderId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(payment.confirmedOrderIds).hasSize(1);
    }

    @Test
    @DisplayName("다른 사용자의 예약은 조회·준비·승인할 수 없다")
    void otherUser() throws Exception {
        UUID reservationId = hold();
        String orderId = checkoutOrderId(reservationId);
        UUID stranger = UUID.randomUUID();

        mockMvc.perform(as(get("/api/v1/reservations/{id}", reservationId), stranger))
                .andExpect(status().isForbidden());
        checkout(reservationId, stranger).andExpect(status().isForbidden());
        confirm(reservationId, orderId, stranger).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/reservations/{id}", reservationId), userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.pgAuthToken").doesNotExist());
    }

    @Test
    @DisplayName("선점 시간이 지난 예약은 결제를 준비할 수 없고, 금액과 인증값이 없는 승인 요청은 400이다")
    void rejectsExpiredAndInvalidRequests() throws Exception {
        UUID reservationId = hold();
        clock.advance(Duration.ofMinutes(10));

        checkout(reservationId, userId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        mockMvc.perform(as(post("/api/v1/reservations/{id}/payments/confirm", reservationId), userId)
                        .content("{\"orderId\": \"order-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(payment.prepared).isEmpty();
    }

    @Test
    @DisplayName("준비 결과를 기록하기 전에 다른 준비가 먼저 기록되면 CHECKOUT_CONFLICT")
    void checkoutConflict() {
        UUID reservationId = hold();
        CheckoutTarget first = reservationTransactions.loadCheckoutTarget(userId, reservationId);
        CheckoutTarget second = reservationTransactions.loadCheckoutTarget(userId, reservationId);

        reservationTransactions.recordCheckout(second, "order-2", 20_000L);

        assertThatThrownBy(() -> reservationTransactions.recordCheckout(first, "order-1", 20_000L))
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.CHECKOUT_CONFLICT);
        assertThat(row(reservationId).get("order_id")).isEqualTo("order-2");
    }

    @Test
    @DisplayName("지난 시도의 실패는 무시하고, 지난 시도의 성공과 끝난 예약의 성공은 F6으로 센다. 같은 성공이 다시 오면 무시한다")
    void staleAndDuplicateResults() throws Exception {
        UUID reservationId = hold();
        String oldOrderId = checkoutOrderId(reservationId);
        String orderId = checkoutOrderId(reservationId);
        payment.confirmTimesOut();
        confirm(reservationId, orderId, userId);
        double lateBefore = lateSuccessCount();

        assertThat(paymentResultApplier.apply(reservationId, oldOrderId, ConfirmOutcome.failed("DECLINED")))
                .isFalse();
        assertThat(row(reservationId).get("status")).isEqualTo("PAYMENT_IN_PROGRESS");

        assertThat(paymentResultApplier.apply(reservationId, oldOrderId, ConfirmOutcome.succeeded()))
                .isFalse();
        assertThat(lateSuccessCount()).isEqualTo(lateBefore + 1);

        assertThat(paymentResultApplier.apply(reservationId, orderId, ConfirmOutcome.succeeded()))
                .isTrue();
        assertThat(paymentResultApplier.apply(reservationId, orderId, ConfirmOutcome.succeeded()))
                .as("같은 성공이 다시 왔다")
                .isFalse();
        assertThat(lateSuccessCount()).isEqualTo(lateBefore + 1);
        assertThat(seats.sold).hasSize(1);
    }
}
