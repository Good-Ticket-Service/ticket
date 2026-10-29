package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.expiry.HoldExpiryService;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.facade.ReleaseResult;
import com.sparta.ticket.reservation.booking.application.facade.SeatLine;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions;
import com.sparta.ticket.reservation.booking.application.payment.PaymentRequestTransactions.CheckoutTarget;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 결제 준비에서 쿠폰을 잡고 할인액을 정하는 일과, 예약이 끝나는 방식에 따라 쿠폰을 사용 처리하거나 돌려주는 일. */
class ReservationCouponIntegrationTest extends BookingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Autowired
    private PaymentRequestTransactions paymentRequestTransactions;

    private final Long userId = TestIds.next();
    private final Long scheduleId = TestIds.next();

    /** 좌석 두 장, 정가 20,000원. */
    private Long hold() {
        return holdPriced(TestSeats.PRICE, TestSeats.PRICE);
    }

    /** 같은 회원의 다른 회차 예약. 좌석 두 장, 정가 20,000원. */
    private Long holdOnOtherSchedule() {
        List<SeatLine> lines = TestSeats.priced(30L, 31L);
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId, TestHoldTargets.of(TestIds.next()), lines, clock.now().plusMinutes(10)));
    }

    private Long holdPriced(int... prices) {
        List<SeatLine> lines = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            lines.add(new SeatLine(20L + i, prices[i]));
        }
        return transactionTemplate.execute(status -> bookingFacade.hold(
                userId, TestHoldTargets.of(scheduleId), lines, clock.now().plusMinutes(10)));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions checkout(Long reservationId, Long userCouponId) throws Exception {
        String body = userCouponId == null
                ? "{\"pgProvider\": \"TOSS\"}"
                : "{\"pgProvider\": \"TOSS\", \"userCouponId\": " + userCouponId + "}";
        return mockMvc.perform(as(post("/api/reservations/{id}/payments/checkout", reservationId))
                .content(body));
    }

    private String checkoutOrderId(Long reservationId, Long userCouponId) throws Exception {
        String body = checkout(reservationId, userCouponId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("data").get("orderId").asText();
    }

    private ResultActions confirm(Long reservationId, String orderId) throws Exception {
        return mockMvc.perform(as(post("/api/reservations/{id}/payments/confirm", reservationId))
                .content(objectMapper.writeValueAsString(Map.of("orderId", orderId, "pgAuthToken", "pk-1"))));
    }

    private PaymentPrepareRequest lastPrepared() {
        return payment.prepared.get(payment.prepared.size() - 1);
    }

    @Nested
    @DisplayName("결제 준비에서 쿠폰 잡기")
    class Checkout {

        @Test
        @DisplayName("쿠폰을 고르면 예약에 잡고 할인액을 결제 준비에 넘기며, 확정되면 사용 처리한다")
        void reservesAndUses() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);

            String orderId = checkoutOrderId(reservationId, coupon);

            assertThat(lastPrepared().totalPrice()).isEqualTo(20_000L);
            assertThat(lastPrepared().discountAmount()).isEqualTo(3_000L);
            assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");
            assertThat(coupons.reservationOf(coupon)).isEqualTo(reservationId);

            confirm(reservationId, orderId)
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.paymentAmount").value(17_000));
            assertThat(coupons.statusOf(coupon)).isEqualTo("USED");
        }

        @Test
        @DisplayName("쓸 수 없는 쿠폰이면 400 COUPON_NOT_AVAILABLE이고, 예약은 HOLD 그대로이며 결제 준비를 부르지 않는다")
        void rejectsUnavailableCoupon() throws Exception {
            Long reservationId = hold();
            Long othersCoupon = coupons.issue(TestIds.next(), 3_000);

            checkout(reservationId, othersCoupon)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_AVAILABLE"));

            assertThat(payment.prepared).isEmpty();
            assertThat(statusOf(reservationId)).isEqualTo("HOLD");
            assertThat(coupons.statusOf(othersCoupon)).isEqualTo("AVAILABLE");
            assertThat(couponColumns(reservationId).get("user_coupon_id"))
                    .as("쿠폰을 잡지 못하면 예약에도 남기지 않는다")
                    .isNull();
        }

        @Test
        @DisplayName("정가가 최소 결제 금액 이하인데 쿠폰을 고르면 400 COUPON_NOT_AVAILABLE이고 쿠폰을 잡지 않는다")
        void rejectsCouponOnMinimumPrice() throws Exception {
            Long reservationId = holdPriced(100);
            Long coupon = coupons.issue(userId, 1_000);

            checkout(reservationId, coupon)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_AVAILABLE"));

            assertThat(payment.prepared).isEmpty();
            assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("할인액이 정가 - 최소 결제 금액을 넘으면 잘라서 결제 금액이 100원이 된다")
        void capsDiscount() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 25_000);

            checkout(reservationId, coupon)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.amount").value(100));

            assertThat(lastPrepared().discountAmount()).isEqualTo(19_900L);
        }

        @Test
        @DisplayName("쿠폰을 바꾸면 이전 쿠폰을 돌려주고 새 쿠폰을 잡는다. 쿠폰을 빼면 잡은 쿠폰을 돌려준다")
        void changesAndRemovesCoupon() throws Exception {
            Long reservationId = hold();
            Long first = coupons.issue(userId, 3_000);
            Long second = coupons.issue(userId, 5_000);

            checkoutOrderId(reservationId, first);
            checkoutOrderId(reservationId, second);

            assertThat(coupons.statusOf(first)).isEqualTo("AVAILABLE");
            assertThat(coupons.statusOf(second)).isEqualTo("RESERVED");
            assertThat(lastPrepared().discountAmount()).isEqualTo(5_000L);

            checkoutOrderId(reservationId, null);

            assertThat(coupons.statusOf(second)).isEqualTo("AVAILABLE");
            assertThat(lastPrepared().discountAmount()).isZero();
        }

        @Test
        @DisplayName("같은 쿠폰으로 다시 준비하면 쿠폰을 다시 잡지 않고 같은 할인액을 넘긴다")
        void keepsSameCoupon() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);

            checkoutOrderId(reservationId, coupon);
            checkoutOrderId(reservationId, coupon);

            assertThat(payment.prepared)
                    .extracting(PaymentPrepareRequest::discountAmount)
                    .containsExactly(3_000L, 3_000L);
            assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("쿠폰을 바꾼 준비가 끝나기 전에 지난 준비의 승인이 오면, 지난 할인액으로 결제하지 않고 ORDER_ID_MISMATCH다")
        void staleConfirmAfterCouponChange() throws Exception {
            Long reservationId = hold();
            Long first = coupons.issue(userId, 3_000);
            Long second = coupons.issue(userId, 5_000);
            String firstOrderId = checkoutOrderId(reservationId, first);
            AtomicReference<String> staleConfirm = new AtomicReference<>();

            payment.beforeNextPrepare(() -> {
                try {
                    staleConfirm.set(confirm(reservationId, firstOrderId)
                            .andReturn()
                            .getResponse()
                            .getContentAsString());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            ResultActions changed = checkout(reservationId, second);

            assertThat(payment.confirmedOrderIds)
                    .as("쿠폰을 바꾼 뒤에는 지난 할인액의 주문을 승인하지 않는다")
                    .isEmpty();
            JsonNode stale = objectMapper.readTree(staleConfirm.get());
            assertThat(stale.get("code").asText()).isEqualTo("ORDER_ID_MISMATCH");
            changed.andExpect(status().isOk());
            assertThat(statusOf(reservationId)).isEqualTo("HOLD");
        }
    }

    @Test
    @DisplayName("같은 예약에 다른 쿠폰으로 준비 두 개가 겹치면 먼저 반영된 쪽만 쿠폰을 잡고, 나머지는 CHECKOUT_CONFLICT다")
    void concurrentCouponChange() {
        Long reservationId = hold();
        Long first = coupons.issue(userId, 3_000);
        Long second = coupons.issue(userId, 5_000);
        CheckoutTarget earlier = paymentRequestTransactions.loadCheckoutTarget(userId, reservationId);
        CheckoutTarget later = paymentRequestTransactions.loadCheckoutTarget(userId, reservationId);

        paymentRequestTransactions.applyCoupon(earlier, first);

        assertThatThrownBy(() -> paymentRequestTransactions.applyCoupon(later, second))
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.CHECKOUT_CONFLICT);
        assertThat(coupons.statusOf(first)).isEqualTo("RESERVED");
        assertThat(coupons.statusOf(second)).as("진 쪽은 쿠폰을 잡지 않는다").isEqualTo("AVAILABLE");
        assertThat(((Number) couponColumns(reservationId).get("user_coupon_id")).longValue())
                .isEqualTo(first);
    }

    @Test
    @DisplayName("쿠폰을 바꾼 준비가 결제 응답을 기다리는 사이 다른 준비가 쿠폰을 또 바꾸면, 앞선 준비는 바뀌기 전 할인액으로 기록하지 않는다")
    void couponChangedDuringPrepare() throws Exception {
        Long reservationId = hold();
        Long first = coupons.issue(userId, 3_000);
        Long second = coupons.issue(userId, 5_000);
        payment.beforeNextPrepare(() -> paymentRequestTransactions.applyCoupon(
                paymentRequestTransactions.loadCheckoutTarget(userId, reservationId), second));

        checkout(reservationId, first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKOUT_CONFLICT"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT order_id, payment_amount, user_coupon_id FROM p_reservations WHERE reservation_id = ?",
                reservationId);
        assertThat(row.get("order_id"))
                .as("쿠폰이 바뀐 뒤에는 바뀌기 전 할인액으로 만든 주문을 기록하지 않는다")
                .isNull();
        assertThat(row.get("payment_amount")).isNull();
        assertThat(((Number) row.get("user_coupon_id")).longValue()).isEqualTo(second);
    }

    @Test
    @DisplayName("같은 회원의 두 예약이 서로의 쿠폰으로 동시에 바꾸면 둘 다 500 없이 거절되고, 예약과 쿠폰은 그대로다")
    void crossSwapIsRejectedWithoutServerError() throws Exception {
        Long first = hold();
        Long second = holdOnOtherSchedule();
        Long couponX = coupons.issue(userId, 3_000);
        Long couponY = coupons.issue(userId, 5_000);
        checkoutOrderId(first, couponX);
        checkoutOrderId(second, couponY);
        coupons.syncReservesWith(new CyclicBarrier(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> firstSwap = executor.submit(() -> errorCode(checkout(first, couponY)));
            Future<String> secondSwap = executor.submit(() -> errorCode(checkout(second, couponX)));

            // Note: 상대 쿠폰은 상대 트랜잭션이 돌려주는 중이라 아직 이 예약이 잡을 수 없다. 쿠폰 쪽 구현이 그 행을 기다리면
            // 교착으로 한쪽이 CHECKOUT_CONFLICT, 기다리지 않으면 둘 다 COUPON_NOT_AVAILABLE이다. 어느 쪽이든 500은 아니다.
            assertThat(List.of(firstSwap.get(10, TimeUnit.SECONDS), secondSwap.get(10, TimeUnit.SECONDS)))
                    .allSatisfy(result -> assertThat(result).isIn("409 CHECKOUT_CONFLICT", "400 COUPON_NOT_AVAILABLE"));
        } finally {
            executor.shutdownNow();
        }
        assertThat(coupons.reservationOf(couponX)).isEqualTo(first);
        assertThat(coupons.reservationOf(couponY)).isEqualTo(second);
        assertThat(((Number) couponColumns(first).get("user_coupon_id")).longValue())
                .isEqualTo(couponX);
        assertThat(((Number) couponColumns(second).get("user_coupon_id")).longValue())
                .isEqualTo(couponY);
    }

    @Test
    @DisplayName("쿠폰을 잡다가 DB 교착·잠금 실패가 나면 500이 아니라 409 CHECKOUT_CONFLICT이고, 예약과 쿠폰은 그대로다")
    void lockFailureIsCheckoutConflict() throws Exception {
        Long reservationId = hold();
        Long first = coupons.issue(userId, 3_000);
        Long second = coupons.issue(userId, 5_000);
        checkoutOrderId(reservationId, first);
        coupons.failNextReserveWithLock();

        checkout(reservationId, second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKOUT_CONFLICT"));

        assertThat(coupons.statusOf(first)).isEqualTo("RESERVED");
        assertThat(coupons.statusOf(second)).isEqualTo("AVAILABLE");
        assertThat(((Number) couponColumns(reservationId).get("user_coupon_id")).longValue())
                .isEqualTo(first);
    }

    @Nested
    @DisplayName("예약이 끝날 때 쿠폰 처리")
    class Closing {

        @Test
        @DisplayName("결제가 실패해 HOLD로 돌아가면 쿠폰을 잡은 채로 둔다")
        void keepsCouponOnFailureWithTimeLeft() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            String orderId = checkoutOrderId(reservationId, coupon);
            payment.confirmReturns(ConfirmOutcome.failed());

            confirm(reservationId, orderId).andExpect(jsonPath("$.data.status").value("HOLD"));

            assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("결제가 실패했는데 선점 시간이 지났으면 EXPIRED가 되고 쿠폰을 돌려준다")
        void releasesCouponOnFailureAfterExpiry() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            String orderId = checkoutOrderId(reservationId, coupon);
            payment.confirmAnswers(() -> {
                clock.advance(Duration.ofMinutes(11));
                return ConfirmOutcome.failed();
            });

            confirm(reservationId, orderId).andExpect(jsonPath("$.data.status").value("EXPIRED"));

            assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("결제 결과를 모르면 쿠폰을 잡은 채로 둔다")
        void keepsCouponWhenOutcomeUnknown() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            String orderId = checkoutOrderId(reservationId, coupon);
            payment.confirmTimesOut();

            confirm(reservationId, orderId).andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"));

            assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("만료 스케줄러가 선점을 만료하면 쿠폰을 돌려준다")
        void releasesCouponOnScheduledExpiry() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            checkoutOrderId(reservationId, coupon);
            clock.advance(Duration.ofMinutes(11));

            assertThat(holdExpiryService.expireDue(10, Duration.ofSeconds(5))).isEqualTo(1);

            assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("사용자가 선점을 해제하면 쿠폰을 돌려준다")
        void releasesCouponOnUserRelease() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            checkoutOrderId(reservationId, coupon);

            ReleaseResult result = transactionTemplate.execute(status -> bookingFacade.release(userId, reservationId));

            assertThat(result).isEqualTo(ReleaseResult.RELEASED);
            assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("seat가 시간이 지난 선점을 정리하면 쿠폰을 돌려준다")
        void releasesCouponOnExpireIfDue() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            checkoutOrderId(reservationId, coupon);
            clock.advance(Duration.ofMinutes(11));

            Boolean expired = transactionTemplate.execute(status -> bookingFacade.expireIfDue(reservationId));

            assertThat(expired).isTrue();
            assertThat(coupons.statusOf(coupon)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("좌석이 이 예약의 선점이 아니라 확정하지 못하면 쿠폰도 사용 처리하지 않는다")
        void keepsCouponWhenNotConfirmed() throws Exception {
            Long reservationId = hold();
            Long coupon = coupons.issue(userId, 3_000);
            String orderId = checkoutOrderId(reservationId, coupon);
            seats.notHeld(20L);

            confirm(reservationId, orderId);

            assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
            assertThat(coupons.statusOf(coupon)).isEqualTo("RESERVED");
        }
    }

    private String errorCode(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        String code = objectMapper
                .readTree(response.getContentAsString())
                .path("code")
                .asText();
        return response.getStatus() + " " + code;
    }

    private Map<String, Object> couponColumns(Long reservationId) {
        return jdbcTemplate.queryForMap(
                "SELECT user_coupon_id, discount_amount FROM p_reservations WHERE reservation_id = ?", reservationId);
    }
}
