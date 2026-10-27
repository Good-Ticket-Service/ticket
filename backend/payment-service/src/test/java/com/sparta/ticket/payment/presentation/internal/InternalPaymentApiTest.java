package com.sparta.ticket.payment.presentation.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 결제 준비·조회 내부 API의 요청·응답·오류 형식이 계약(docs/shared/internal-api/payment.md)과 같은지 확인한다. */
class InternalPaymentApiTest extends PaymentIntegrationTest {

    private static final AtomicLong RESERVATION_IDS = new AtomicLong(3_000_000);

    @Autowired
    private MockMvc mockMvc;

    private static String body(String orderId, long reservationId, String provider, int totalPrice, int discount) {
        return """
                {"orderId":"%s","reservationId":%d,"userId":1,"sellerId":2,"performanceId":10,"scheduleId":20,
                 "totalPrice":%d,"discountAmount":%d,"pgProvider":"%s","expiresAt":"2099-11-01T20:10:00+09:00"}
                """.formatted(orderId, reservationId, totalPrice, discount, provider);
    }

    private ResultActions checkout(String body) throws Exception {
        return mockMvc.perform(post("/internal/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("준비 응답은 감싸지 않은 계약 형식이고, 조회는 같은 결제를 돌려준다")
    void checkoutAndFind() throws Exception {
        String orderId = newOrderId();
        long reservationId = RESERVATION_IDS.incrementAndGet();

        checkout(body(orderId, reservationId, "KAKAO", 50_000, 5_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.amount").value(45_000))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.checkoutUrl").isNotEmpty());

        mockMvc.perform(get("/internal/payments/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.amount").value(45_000))
                .andExpect(jsonPath("$.confirmRequestedAt").doesNotExist())
                .andExpect(jsonPath("$.refund").doesNotExist());
    }

    @Test
    @DisplayName("카카오 준비 실패는 200에 FAILED·failureType CLOSED·failureCode READY_FAILED로 온다")
    void readyFailedIsDistinguishable() throws Exception {
        pg.readyReturns(() -> new PgReadyResult.Failed("카카오 장애"));

        checkout(body(newOrderId(), RESERVATION_IDS.incrementAndGet(), "KAKAO", 50_000, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureType").value("CLOSED"))
                .andExpect(jsonPath("$.failureCode").value("READY_FAILED"));
    }

    @Test
    @DisplayName("같은 orderId에 다른 할인액은 409 PAYMENT_ORDER_CONFLICT")
    void orderConflict() throws Exception {
        String orderId = newOrderId();
        long reservationId = RESERVATION_IDS.incrementAndGet();
        checkout(body(orderId, reservationId, "TOSS", 50_000, 5_000)).andExpect(status().isOk());

        checkout(body(orderId, reservationId, "TOSS", 50_000, 4_000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_CONFLICT"));
    }

    @Test
    @DisplayName("금액 경계: 음수 할인·초과 할인·99원은 INVALID_PAYMENT_AMOUNT, 100원은 성공, 정가 0은 VALIDATION_ERROR")
    void amountBoundaries() throws Exception {
        checkout(body(newOrderId(), RESERVATION_IDS.incrementAndGet(), "TOSS", 50_000, -1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_AMOUNT"));
        checkout(body(newOrderId(), RESERVATION_IDS.incrementAndGet(), "TOSS", 50_000, 60_000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_AMOUNT"));
        checkout(body(newOrderId(), RESERVATION_IDS.incrementAndGet(), "TOSS", 1_000, 901))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_AMOUNT"));
        checkout(body(newOrderId(), RESERVATION_IDS.incrementAndGet(), "TOSS", 2_147_483_647, 2_147_483_547))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100));
        checkout(body(newOrderId(), RESERVATION_IDS.incrementAndGet(), "TOSS", 0, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("필수 필드가 없으면 VALIDATION_ERROR, 없는 주문 조회는 404 PAYMENT_NOT_FOUND")
    void validationAndNotFound() throws Exception {
        checkout("{\"orderId\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/internal/payments/{orderId}", "none-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
