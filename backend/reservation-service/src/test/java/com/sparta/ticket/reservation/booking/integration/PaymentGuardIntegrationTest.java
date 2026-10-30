package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.infrastructure.payment.PaymentGuardProperties;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * payment가 응답하지 않는 일이 이어지면 사용자 요청의 결제 준비·승인을 payment를 부르지 않고 바로 503으로 끝낸다.
 * 승인은 결제 중으로 바꾸기 전에 막으므로 예약은 HOLD 그대로이고, 사용자는 선점 시간 안에 다시 시도할 수 있다.
 * 스케줄러의 호출은 막지 않는다. 다시 보내야 끝나는 일이고 사용자 요청 스레드를 쓰지 않는다.
 */
class PaymentGuardIntegrationTest extends BookingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private PaymentReconcileService reconcileService;

    @Autowired
    private PaymentGuardProperties guardProperties;

    private final Long userId = TestIds.next();

    private Long hold() {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId,
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions checkout(Long reservationId) throws Exception {
        return mockMvc.perform(as(post("/api/reservations/{id}/payments/checkout", reservationId))
                .content("{\"pgProvider\": \"TOSS\"}"));
    }

    private String orderIdOf(ResultActions checkout) throws Exception {
        String body = checkout.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("orderId").asText();
    }

    private String checkoutOrderId(Long reservationId) throws Exception {
        return orderIdOf(checkout(reservationId).andExpect(status().isOk()));
    }

    private ResultActions confirm(Long reservationId, String orderId) throws Exception {
        return mockMvc.perform(as(post("/api/reservations/{id}/payments/confirm", reservationId))
                .content(objectMapper.writeValueAsString(Map.of("orderId", orderId, "pgAuthToken", "pk-1"))));
    }

    /**
     * 승인 응답이 없는 시도를 회로가 열릴 때까지 만들고 그 orderId를 돌려준다. 각 예약은 결제 중으로 남는다.
     * 결제 준비도 같은 회로를 쓰므로 준비가 503이 되면 열린 것이다. 준비 성공과 승인 무응답이 반씩 쌓여 최소 호출 수에서 열린다.
     */
    private List<String> unansweredUntilOpen() throws Exception {
        payment.confirmTimesOut();
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < guardProperties.windowSize(); i++) {
            Long reservationId = hold();
            ResultActions checkout = checkout(reservationId);
            if (checkout.andReturn().getResponse().getStatus() == 503) {
                return orderIds;
            }
            String orderId = orderIdOf(checkout);
            confirm(reservationId, orderId).andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"));
            orderIds.add(orderId);
        }
        throw new AssertionError("응답 없는 승인이 이어졌는데 회로가 열리지 않았다");
    }

    @Test
    @DisplayName("승인 응답이 계속 없으면 다음 승인은 payment를 부르지 않고 503이며 예약은 HOLD 그대로다")
    void opensOnUnansweredConfirms() throws Exception {
        Long waiting = hold();
        String orderId = checkoutOrderId(waiting);
        unansweredUntilOpen();
        int calls = payment.confirmedOrderIds.size();

        confirm(waiting, orderId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        assertThat(payment.confirmedOrderIds).hasSize(calls);
        assertThat(statusOf(waiting)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("payment가 승인 결과를 모른다고 답하는 일이 이어지면(PG 멈춤) 연다")
    void opensOnUnknownConfirmAnswers() throws Exception {
        payment.confirmReturns(ConfirmOutcome.unknown());
        for (int i = 0; i < guardProperties.windowSize(); i++) {
            Long reservationId = hold();
            ResultActions checkout = checkout(reservationId);
            if (checkout.andReturn().getResponse().getStatus() == 503) {
                return;
            }
            confirm(reservationId, orderIdOf(checkout))
                    .andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"));
        }
        throw new AssertionError("결과 모름이 이어졌는데 회로가 열리지 않았다");
    }

    @Test
    @DisplayName("회로가 열려 있으면 결제 준비도 payment를 부르지 않고 503이다")
    void blocksCheckoutWhileOpen() throws Exception {
        unansweredUntilOpen();
        Long reservationId = hold();
        int prepares = payment.prepared.size();

        checkout(reservationId).andExpect(status().isServiceUnavailable());

        assertThat(payment.prepared).hasSize(prepares);
        assertThat(statusOf(reservationId)).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("payment가 답한 결제 거절은 장애가 아니므로 회로를 열지 않는다")
    void businessFailureKeepsClosed() throws Exception {
        payment.confirmReturns(ConfirmOutcome.failed());
        for (int i = 0; i < guardProperties.windowSize(); i++) {
            Long reservationId = hold();
            confirm(reservationId, checkoutOrderId(reservationId))
                    .andExpect(jsonPath("$.data.status").value("HOLD"));
        }
        payment.confirmReturns(ConfirmOutcome.succeeded());
        Long reservationId = hold();

        confirm(reservationId, checkoutOrderId(reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("payment가 4xx 업무 오류로 바로 답한 승인은 응답이 있었던 것이므로 회로를 열지 않는다")
    void businessErrorAnswerKeepsClosed() throws Exception {
        payment.confirmAnswers(() -> {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        });
        for (int i = 0; i < guardProperties.windowSize(); i++) {
            Long reservationId = hold();
            confirm(reservationId, checkoutOrderId(reservationId)).andExpect(status().isOk());
        }
        Long reservationId = hold();

        checkout(reservationId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("동시에 기다리는 승인이 한도에 차면 다음 승인은 payment를 부르지 않고 503이며, 자리가 나면 다시 받는다")
    void limitsConcurrentConfirms() throws Exception {
        int limit = guardProperties.maxConcurrentCalls();
        List<Long> slow = new ArrayList<>();
        List<String> slowOrders = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Long reservationId = hold();
            slow.add(reservationId);
            slowOrders.add(checkoutOrderId(reservationId));
        }
        Long waiting = hold();
        String waitingOrder = checkoutOrderId(waiting);
        CountDownLatch arrived = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        payment.confirmAnswers(() -> {
            arrived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ConfirmOutcome.succeeded();
        });
        ExecutorService pool = Executors.newFixedThreadPool(limit);
        List<Future<ResultActions>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < limit; i++) {
                Long reservationId = slow.get(i);
                String orderId = slowOrders.get(i);
                pending.add(pool.submit(() -> confirm(reservationId, orderId)));
            }
            assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue();

            confirm(waiting, waitingOrder).andExpect(status().isServiceUnavailable());

            assertThat(payment.confirmedOrderIds).doesNotContain(waitingOrder);
            assertThat(statusOf(waiting)).isEqualTo("HOLD");
        } finally {
            release.countDown();
            pool.shutdown();
        }
        for (Future<ResultActions> future : pending) {
            future.get(10, TimeUnit.SECONDS).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        }
        confirm(waiting, waitingOrder).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("회로가 열려 있어도 결과 확인 스케줄러는 승인을 다시 보내 결제 중인 예약을 끝낸다")
    void reconcileIgnoresCircuit() throws Exception {
        List<String> pending = unansweredUntilOpen();
        for (String orderId : pending) {
            payment.lookupReturns(orderId, PaymentLookup.of(PaymentLookup.Status.NOT_RECEIVED));
        }
        payment.confirmReturns(ConfirmOutcome.succeeded());
        clock.advance(Duration.ofMinutes(2));

        int finished =
                reconcileService.reconcile(Duration.ofMinutes(1), Duration.ofMinutes(30), 100, Duration.ofSeconds(30));

        assertThat(finished).isEqualTo(pending.size());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM p_reservations WHERE status = 'CONFIRMED'", Integer.class))
                .isEqualTo(pending.size());
        checkout(hold()).andExpect(status().isServiceUnavailable());
    }
}
