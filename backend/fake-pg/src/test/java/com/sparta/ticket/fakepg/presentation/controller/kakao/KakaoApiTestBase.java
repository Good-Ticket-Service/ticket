package com.sparta.ticket.fakepg.presentation.controller.kakao;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.support.FakePgIntegrationTest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 카카오 호환 API 테스트의 요청 도우미. 준비 → 사용자 승인 → 승인 순서를 짧게 쓰게 한다. */
abstract class KakaoApiTestBase extends FakePgIntegrationTest {

    protected static final String USER_ID = "user-1";

    @Autowired
    protected ObjectMapper objectMapper;

    protected String orderId;
    protected String tid;

    protected String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    protected ResultActions readyRequest(String orderId, String userId, int amount) throws Exception {
        return mockMvc.perform(post("/online/v1/payment/ready")
                .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cid":"%s","partner_order_id":"%s","partner_user_id":"%s","item_name":"공연 티켓",
                         "quantity":1,"total_amount":%d,"tax_free_amount":0,
                         "approval_url":"http://localhost/approve","cancel_url":"http://localhost/cancel",
                         "fail_url":"http://localhost/fail"}
                        """.formatted(KAKAO_CID, orderId, userId, amount)));
    }

    /** 새 주문을 준비하고 tid를 받아 둔다. */
    protected void ready(int amount) throws Exception {
        orderId = newOrderId();
        String body = readyRequest(orderId, USER_ID, amount)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        tid = objectMapper.readTree(body).get("tid").asText();
    }

    protected ResultActions userApproveRequest(String tid) throws Exception {
        return mockMvc.perform(post("/fake/kakao/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tid\":\"" + tid + "\"}"));
    }

    /** 사용자 승인 시뮬레이션으로 pg_token을 받는다. */
    protected String userApprove() throws Exception {
        String body = userApproveRequest(tid)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("pg_token").asText();
    }

    protected ResultActions approve(String pgToken) throws Exception {
        return approve(tid, orderId, USER_ID, pgToken);
    }

    protected ResultActions approve(String tid, String orderId, String userId, String pgToken) throws Exception {
        return mockMvc.perform(post("/online/v1/payment/approve")
                .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cid":"%s","tid":"%s","partner_order_id":"%s","partner_user_id":"%s","pg_token":"%s"}
                        """.formatted(KAKAO_CID, tid, orderId, userId, pgToken)));
    }

    protected ResultActions order(String tid) throws Exception {
        return mockMvc.perform(post("/online/v1/payment/order")
                .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cid\":\"" + KAKAO_CID + "\",\"tid\":\"" + tid + "\"}"));
    }

    protected ResultActions cancel(int amount) throws Exception {
        return mockMvc.perform(post("/online/v1/payment/cancel")
                .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cid":"%s","tid":"%s","cancel_amount":%d,"cancel_tax_free_amount":0}
                        """.formatted(KAKAO_CID, tid, amount)));
    }

    protected String pgStatus() throws Exception {
        String body = order(tid).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("status").asText();
    }
}
