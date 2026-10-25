package com.sparta.ticket.fakepg.presentation.controller.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.support.FakePgIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** 장애 없이 토스 호환 API가 실제 토스처럼 동작하는지 확인한다. */
class TossPaymentApiTest extends FakePgIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    private String checkout(String orderId, int amount) throws Exception {
        String body = mockMvc.perform(post("/fake/toss/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("paymentKey").asText();
    }

    private String confirmBody(String paymentKey, String orderId, int amount) {
        return "{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}";
    }

    @Test
    @DisplayName("결제 인증 → 승인 → 조회 → 취소 → 조회가 토스 형식으로 끝까지 된다")
    void fullFlow() throws Exception {
        String orderId = newOrderId();
        String paymentKey = checkout(orderId, 50_000);

        mockMvc.perform(get("/v1/payments/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(paymentKey, orderId, 50_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.paymentKey").value(paymentKey))
                .andExpect(jsonPath("$.totalAmount").value(50_000))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());

        mockMvc.perform(get("/v1/payments/" + paymentKey).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        mockMvc.perform(post("/v1/payments/" + paymentKey + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"예매 취소\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.balanceAmount").value(0))
                .andExpect(jsonPath("$.cancels[0].cancelAmount").value(50_000));

        mockMvc.perform(get("/v1/payments/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("시크릿 키가 틀리면 401 UNAUTHORIZED_KEY")
    void rejectsWrongSecretKey() throws Exception {
        mockMvc.perform(get("/v1/payments/orders/any").header(HttpHeaders.AUTHORIZATION, "Basic wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_KEY"));
    }

    @Test
    @DisplayName("업무 규칙: 금액 불일치 400, 없는 paymentKey 404, 두 번째 승인 400, 없는 주문 조회 404")
    void businessErrors() throws Exception {
        String orderId = newOrderId();
        String paymentKey = checkout(orderId, 10_000);

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(paymentKey, orderId, 9_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("tgen_unknown", orderId, 10_000)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND_PAYMENT_SESSION"));

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(paymentKey, orderId, 10_000)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(paymentKey, orderId, 10_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALREADY_PROCESSED_PAYMENT"));

        mockMvc.perform(get("/v1/payments/orders/none-" + orderId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND_PAYMENT"));
    }

    @Test
    @DisplayName("이미 취소된 결제를 다시 취소하면 400, 승인 전 결제는 취소할 수 없다")
    void cancelRules() throws Exception {
        String orderId = newOrderId();
        String paymentKey = checkout(orderId, 10_000);

        mockMvc.perform(post("/v1/payments/" + paymentKey + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"취소\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CANCELABLE_PAYMENT"));

        mockMvc.perform(post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(paymentKey, orderId, 10_000)));
        mockMvc.perform(post("/v1/payments/" + paymentKey + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelReason\":\"취소\"}"));

        mockMvc.perform(post("/v1/payments/" + paymentKey + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"취소\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELED_PAYMENT"));
    }

    @Test
    @DisplayName("같은 주문으로 다시 인증하면 인증 유효 시간 안에는 같은 paymentKey를 돌려준다")
    void reauthorizeReturnsSameKey() throws Exception {
        String orderId = newOrderId();

        assertThat(checkout(orderId, 10_000)).isEqualTo(checkout(orderId, 10_000));
    }

    @Test
    @DisplayName("관리 API로 PG 쪽 거래 기록을 주문번호로 조회한다")
    void adminTransactions() throws Exception {
        String orderId = newOrderId();
        checkout(orderId, 10_000);

        String body = mockMvc.perform(get("/fake/admin/transactions").param("orderId", orderId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode list = objectMapper.readTree(body);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("status").asText()).isEqualTo("READY");
    }
}
