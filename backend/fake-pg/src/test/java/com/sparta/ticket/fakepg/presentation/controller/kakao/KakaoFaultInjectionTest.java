package com.sparta.ticket.fakepg.presentation.controller.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 관리 API로 카카오 API에 건 장애가 토스와 같이 동작하는지 확인한다. 카카오에는 멱등 키가 없어,
 * 응답 유실 뒤 같은 요청을 다시 보내면 재현이 아니라 "이미 처리됨" 오류가 나는 점이 토스와 다르다.
 */
class KakaoFaultInjectionTest extends KakaoApiTestBase {

    private void fault(String operation, String json) throws Exception {
        mockMvc.perform(put("/fake/admin/faults/KAKAO/" + operation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private void clearFaults() throws Exception {
        mockMvc.perform(delete("/fake/admin/faults")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("준비 5xx: 처리하지 않고 500, 거래가 생기지 않는다")
    void readyServerError() throws Exception {
        fault("READY", "{\"serverErrorRate\":1}");
        orderId = newOrderId();

        readyRequest(orderId, USER_ID, 10_000)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error_code").value(-500));

        clearFaults();
        readyRequest(orderId, USER_ID, 10_000).andExpect(status().isOk());
    }

    @Test
    @DisplayName("준비 응답 유실: tid는 발급됐는데 응답은 500이다")
    void readyResponseLoss() throws Exception {
        fault("READY", "{\"responseLossRate\":1,\"holdMs\":50}");
        orderId = newOrderId();

        readyRequest(orderId, USER_ID, 10_000).andExpect(status().isInternalServerError());

        mockMvc.perform(get("/fake/admin/transactions").param("orderId", orderId))
                .andExpect(jsonPath("$[0].provider").value("KAKAO"))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    @DisplayName("승인 거절: 400 -780이고 거래는 FAIL_PAYMENT")
    void approveRejection() throws Exception {
        ready(10_000);
        String pgToken = userApprove();
        fault("CONFIRM", "{\"failRate\":1}");

        approve(pgToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-780));
        clearFaults();

        assertThat(pgStatus()).isEqualTo("FAIL_PAYMENT");
    }

    @Test
    @DisplayName("승인 응답 유실: 승인은 기록됐는데 응답은 500, 다시 보내면 멱등 키가 없어 -702 이미 처리됨")
    void approveResponseLoss() throws Exception {
        ready(10_000);
        String pgToken = userApprove();
        fault("CONFIRM", "{\"responseLossRate\":1,\"holdMs\":50}");

        approve(pgToken).andExpect(status().isInternalServerError());
        clearFaults();

        assertThat(pgStatus()).isEqualTo("SUCCESS_PAYMENT");
        approve(pgToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-702));
    }

    @Test
    @DisplayName("지연 승인: 응답은 500, 한동안 AUTH_PASSWORD로 조회되다가 나중에 SUCCESS_PAYMENT가 된다")
    void delayedApproval() throws Exception {
        ready(10_000);
        String pgToken = userApprove();
        fault("CONFIRM", "{\"delayedApprovalRate\":1,\"delayedApprovalMs\":500,\"holdMs\":50}");

        approve(pgToken).andExpect(status().isInternalServerError());
        assertThat(pgStatus()).isEqualTo("AUTH_PASSWORD");

        String status = "AUTH_PASSWORD";
        for (int i = 0; i < 40 && "AUTH_PASSWORD".equals(status); i++) {
            Thread.sleep(100);
            status = pgStatus();
        }
        assertThat(status).isEqualTo("SUCCESS_PAYMENT");
    }

    @Test
    @DisplayName("취소 응답 유실: 취소는 기록됐는데 응답은 500, 다시 보내면 -721 이미 취소됨")
    void cancelResponseLoss() throws Exception {
        ready(10_000);
        approve(userApprove()).andExpect(status().isOk());
        fault("CANCEL", "{\"responseLossRate\":1,\"holdMs\":50}");

        cancel(10_000).andExpect(status().isInternalServerError());
        clearFaults();

        assertThat(pgStatus()).isEqualTo("CANCEL_PAYMENT");
        cancel(10_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-721));
    }

    @Test
    @DisplayName("취소 거절: 400 -722이고 거래는 SUCCESS_PAYMENT 그대로")
    void cancelRejection() throws Exception {
        ready(10_000);
        approve(userApprove()).andExpect(status().isOk());
        fault("CANCEL", "{\"failRate\":1}");

        cancel(10_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-722));
        clearFaults();

        assertThat(pgStatus()).isEqualTo("SUCCESS_PAYMENT");
    }

    @Test
    @DisplayName("처리 한도: 초당 한도를 넘으면 429 카카오 형식")
    void rateLimit() throws Exception {
        ready(10_000);
        fault("INQUIRE", "{\"rateLimitPerSecond\":1}");

        int tooMany = 0;
        for (int i = 0; i < 3; i++) {
            if (order(tid).andReturn().getResponse().getStatus() == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).isPositive();
    }

    @Test
    @DisplayName("카카오 장애는 토스 API에 영향을 주지 않는다")
    void faultIsPerProvider() throws Exception {
        fault("INQUIRE", "{\"serverErrorRate\":1}");

        mockMvc.perform(get("/v1/payments/orders/none").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("외부 부분 취소: PART_CANCEL_PAYMENT, 취소액과 취소 가능 금액이 나뉜다")
    void externalPartialCancel() throws Exception {
        ready(10_000);
        approve(userApprove()).andExpect(status().isOk());

        mockMvc.perform(post("/fake/admin/transactions/KAKAO/" + orderId + "/external-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelAmount\":4000}"))
                .andExpect(status().isOk());

        order(tid)
                .andExpect(jsonPath("$.status").value("PART_CANCEL_PAYMENT"))
                .andExpect(jsonPath("$.canceled_amount.total").value(4000))
                .andExpect(jsonPath("$.cancel_available_amount.total").value(6000));
    }
}
