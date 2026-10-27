package com.sparta.ticket.payment.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** fake PG의 테스트 전용 API(결제창 대체, 장애 설정, 거래 기록) 호출 도우미. */
public class FakePgAdmin {

    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FakePgAdmin(String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 토스 결제창에서 사용자가 인증을 마친 것처럼 paymentKey를 받는다. */
    public String tossCheckout(String orderId, int amount) {
        return post("/fake/toss/checkout", Map.of("orderId", orderId, "amount", amount))
                .path("paymentKey")
                .asText();
    }

    /** 카카오 결제창에서 사용자가 인증을 마친 것처럼 pg_token을 받는다. */
    public String kakaoUserApprove(String tid) {
        return post("/fake/kakao/approve", Map.of("tid", tid)).path("pg_token").asText();
    }

    /** 예: {@code fault("TOSS", "CONFIRM", "{\"failRate\":1}")} */
    public void fault(String provider, String operation, String json) {
        client.put()
                .uri("/fake/admin/faults/{provider}/{operation}", provider, operation)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .toBodilessEntity();
    }

    public void clearFaults() {
        client.delete().uri("/fake/admin/faults").retrieve().toBodilessEntity();
    }

    /** PG 쪽 거래 기록의 상태(READY, DONE, CANCELED 등). */
    public String transactionStatus(String orderId) {
        JsonNode list = readTree(client.get()
                .uri("/fake/admin/transactions?orderId={orderId}", orderId)
                .retrieve()
                .body(String.class));
        return list.path(0).path("status").asText();
    }

    private JsonNode post(String path, Object body) {
        return readTree(client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("fake PG 응답을 해석하지 못했다: " + body, e);
        }
    }
}
