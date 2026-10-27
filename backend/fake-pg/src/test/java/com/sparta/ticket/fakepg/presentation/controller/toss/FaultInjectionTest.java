package com.sparta.ticket.fakepg.presentation.controller.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.support.FakePgIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리 API로 건 장애가 설계(docs/architecture.md의 측정·검증)대로 일어나는지 확인한다.
 * 확률은 0 또는 1로 걸어 결과를 고정한다.
 */
class FaultInjectionTest extends FakePgIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private String orderId;
    private String paymentKey;

    private void prepare(int amount) throws Exception {
        orderId = "order-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/fake/toss/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        paymentKey = objectMapper.readTree(body).get("paymentKey").asText();
    }

    private void fault(String operation, String json) throws Exception {
        mockMvc.perform(put("/fake/admin/faults/TOSS/" + operation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private void clearFaults() throws Exception {
        mockMvc.perform(delete("/fake/admin/faults")).andExpect(status().isNoContent());
    }

    private ResultActions confirm(String idempotencyKey) throws Exception {
        var request = post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId + "\",\"amount\":10000}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private ResultActions cancel(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/v1/payments/" + paymentKey + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelReason\":\"예매 취소\"}"));
    }

    private String pgStatus() throws Exception {
        String body = mockMvc.perform(
                        get("/v1/payments/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("status").asText();
    }

    @Test
    @DisplayName("5xx(처리 안 함): 거래는 READY 그대로이고, 같은 멱등 키로 다시 보내면 처리된다")
    void serverErrorIsNotProcessedNorStored() throws Exception {
        prepare(10_000);
        fault("CONFIRM", "{\"serverErrorRate\":1}");

        confirm("key-5xx")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("FAILED_INTERNAL_SYSTEM_PROCESSING"));
        clearFaults();

        assertThat(pgStatus()).isEqualTo("READY");
        confirm("key-5xx")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("승인 거절: 403이고 거래는 ABORTED, 같은 멱등 키로 다시 보내면 장애를 풀어도 같은 거절을 재현한다")
    void rejectionIsStoredForIdempotencyKey() throws Exception {
        prepare(10_000);
        fault("CONFIRM", "{\"failRate\":1}");

        confirm("key-reject")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REJECT_CARD_PAYMENT"));
        clearFaults();

        assertThat(pgStatus()).isEqualTo("ABORTED");
        confirm("key-reject")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REJECT_CARD_PAYMENT"));
    }

    @Test
    @DisplayName("응답 유실: 승인은 기록됐는데 응답은 500, 같은 멱등 키로 다시 보내면 저장된 성공 응답이 나온다")
    void responseLossKeepsApproval() throws Exception {
        prepare(10_000);
        fault("CONFIRM", "{\"responseLossRate\":1,\"holdMs\":100}");

        confirm("key-loss").andExpect(status().isInternalServerError());

        assertThat(pgStatus()).isEqualTo("DONE");
        clearFaults();
        confirm("key-loss")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("지연 승인: 응답은 500, 한동안 READY로 조회되다가 나중에 DONE이 된다(늦은 승인)")
    void delayedApproval() throws Exception {
        prepare(10_000);
        fault("CONFIRM", "{\"delayedApprovalRate\":1,\"delayedApprovalMs\":500,\"holdMs\":50}");

        confirm(null).andExpect(status().isInternalServerError());
        assertThat(pgStatus()).isEqualTo("READY");

        String status = "READY";
        for (int i = 0; i < 40 && "READY".equals(status); i++) {
            Thread.sleep(100);
            status = pgStatus();
        }
        assertThat(status).isEqualTo("DONE");
    }

    @Test
    @DisplayName("처리 한도: 초당 한도를 넘으면 429 TOO_MANY_REQUESTS")
    void rateLimit() throws Exception {
        prepare(10_000);
        fault("INQUIRE", "{\"rateLimitPerSecond\":1}");

        int tooMany = 0;
        for (int i = 0; i < 3; i++) {
            int code = mockMvc.perform(
                            get("/v1/payments/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (code == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).isPositive();
    }

    @Test
    @DisplayName("지연: 설정한 시간만큼 늦게 응답한다")
    void latency() throws Exception {
        prepare(10_000);
        fault("INQUIRE", "{\"latencyMs\":300}");

        long start = System.nanoTime();
        mockMvc.perform(get("/v1/payments/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk());

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("취소 거절: 403 NOT_CANCELABLE_PAYMENT이고 거래는 DONE 그대로, 같은 멱등 키는 같은 거절을 재현한다")
    void cancelRejection() throws Exception {
        prepare(10_000);
        confirm(null).andExpect(status().isOk());
        fault("CANCEL", "{\"failRate\":1}");

        cancel("cancel-key")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CANCELABLE_PAYMENT"));
        clearFaults();

        assertThat(pgStatus()).isEqualTo("DONE");
        cancel("cancel-key").andExpect(status().isForbidden());
        cancel("cancel-key-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("같은 멱등 키로 다른 내용을 보내면 422")
    void sameKeyDifferentRequest() throws Exception {
        prepare(10_000);
        confirm("key-same").andExpect(status().isOk());

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .header("Idempotency-Key", "key-same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId
                                + "\",\"amount\":20000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("장애 설정: 범위를 벗어난 값은 400, 설정한 장애는 목록에 보인다")
    void faultAdmin() throws Exception {
        mockMvc.perform(put("/fake/admin/faults/TOSS/CONFIRM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failRate\":1.5}"))
                .andExpect(status().isBadRequest());

        fault("CONFIRM", "{\"latencyMs\":10}");

        mockMvc.perform(get("/fake/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['TOSS.CONFIRM'].latencyMs").value(10))
                .andExpect(jsonPath("$['TOSS.CONFIRM'].holdMs").value(15_000));
    }
}
