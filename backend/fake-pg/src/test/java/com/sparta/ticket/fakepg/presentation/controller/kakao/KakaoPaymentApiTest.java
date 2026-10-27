package com.sparta.ticket.fakepg.presentation.controller.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** 장애 없이 카카오 호환 API가 실제 카카오처럼 동작하는지 확인한다. */
class KakaoPaymentApiTest extends KakaoApiTestBase {

    @Test
    @DisplayName("준비 → 사용자 승인 → 승인 → 조회 → 취소 → 조회가 카카오 형식으로 끝까지 된다")
    void fullFlow() throws Exception {
        orderId = newOrderId();
        String body = readyRequest(orderId, USER_ID, 50_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tid").isNotEmpty())
                .andExpect(jsonPath("$.next_redirect_pc_url").isNotEmpty())
                .andExpect(jsonPath("$.created_at").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        tid = objectMapper.readTree(body).get("tid").asText();
        assertThat(pgStatus()).isEqualTo("READY");

        String pgToken = userApprove();
        assertThat(pgStatus()).isEqualTo("AUTH_PASSWORD");

        approve(pgToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tid").value(tid))
                .andExpect(jsonPath("$.aid").isNotEmpty())
                .andExpect(jsonPath("$.partner_order_id").value(orderId))
                .andExpect(jsonPath("$.partner_user_id").value(USER_ID))
                .andExpect(jsonPath("$.amount.total").value(50_000))
                .andExpect(jsonPath("$.approved_at").isNotEmpty());

        order(tid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS_PAYMENT"))
                .andExpect(jsonPath("$.cancel_available_amount.total").value(50_000));

        cancel(50_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCEL_PAYMENT"))
                .andExpect(jsonPath("$.canceled_amount.total").value(50_000))
                .andExpect(jsonPath("$.cancel_available_amount.total").value(0));

        assertThat(pgStatus()).isEqualTo("CANCEL_PAYMENT");
    }

    @Test
    @DisplayName("시크릿 키가 틀리면 401, 오류는 카카오 형식이다")
    void rejectsWrongSecretKey() throws Exception {
        mockMvc.perform(post("/online/v1/payment/order")
                        .header(HttpHeaders.AUTHORIZATION, "SECRET_KEY wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cid\":\"" + KAKAO_CID + "\",\"tid\":\"T1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value(-401))
                .andExpect(jsonPath("$.error_message").isNotEmpty());
    }

    @Test
    @DisplayName("입력값 오류와 등록되지 않은 cid는 400 카카오 형식")
    void invalidRequest() throws Exception {
        mockMvc.perform(post("/online/v1/payment/ready")
                        .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cid\":\"" + KAKAO_CID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-2));

        mockMvc.perform(post("/online/v1/payment/order")
                        .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cid\":\"OTHER\",\"tid\":\"T1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-2));
    }

    @Test
    @DisplayName("승인 규칙: 사용자 승인 전, 틀린 pg_token, 다른 회원, 두 번째 승인은 오류다")
    void approveRules() throws Exception {
        ready(10_000);

        approve("no-token")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-701));

        String pgToken = userApprove();
        approve("wrong-token")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-2));
        approve(tid, orderId, "other-user", pgToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-2));
        approve("T-unknown", orderId, USER_ID, pgToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-703));

        approve(pgToken).andExpect(status().isOk());
        approve(pgToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-702));
    }

    @Test
    @DisplayName("취소 규칙: 승인 전 결제와 부분 취소는 오류, 이미 취소된 결제를 다시 취소하면 오류다(멱등 키 없음)")
    void cancelRules() throws Exception {
        ready(10_000);

        cancel(10_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-722));

        approve(userApprove()).andExpect(status().isOk());
        cancel(5_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-2));
        cancel(10_000).andExpect(status().isOk());

        cancel(10_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-721));
    }

    @Test
    @DisplayName("같은 주문으로 다시 준비하면 새 tid를 발급하고, 이전 tid와 사용자 승인은 쓸 수 없다")
    void reReadyIssuesNewTid() throws Exception {
        ready(10_000);
        String firstTid = tid;
        String firstToken = userApprove();

        String body = readyRequest(orderId, USER_ID, 10_000)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        tid = objectMapper.readTree(body).get("tid").asText();

        assertThat(tid).isNotEqualTo(firstTid);
        assertThat(pgStatus()).isEqualTo("READY");
        approve(firstTid, orderId, USER_ID, firstToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-703));

        readyRequest(orderId, USER_ID, 20_000).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토스 거래의 키로 카카오 API를 부르면 없는 거래다")
    void tossTransactionIsNotVisible() throws Exception {
        String tossBody = mockMvc.perform(post("/fake/toss/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + newOrderId() + "\",\"amount\":10000}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String paymentKey = objectMapper.readTree(tossBody).get("paymentKey").asText();

        order(paymentKey)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value(-704));
        userApproveRequest(paymentKey).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리 API 거래 기록에 카카오 거래가 KAKAO로 남는다")
    void adminTransactions() throws Exception {
        ready(10_000);

        mockMvc.perform(get("/fake/admin/transactions").param("orderId", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("KAKAO"))
                .andExpect(jsonPath("$[0].paymentKey").value(tid));
    }
}
