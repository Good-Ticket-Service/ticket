package com.sparta.ticket.fakepg.presentation.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.support.FakePgIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** 장애가 실제로 걸린 횟수와 확률 판단 난수의 seed를 확인한다. */
class FaultStatsAndSeedTest extends FakePgIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private void fault(String operation, String json) throws Exception {
        mockMvc.perform(put("/fake/admin/faults/TOSS/" + operation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private void confirmNewOrder() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/fake/toss/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":10000}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String paymentKey = objectMapper.readTree(body).get("paymentKey").asText();
        mockMvc.perform(post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId + "\",\"amount\":10000}"));
    }

    @Test
    @DisplayName("장애가 걸린 횟수를 요청 수와 함께 센다. 장애 설정을 풀어도 남고, 따로 비운다")
    void countsAppliedFaults() throws Exception {
        fault("CONFIRM", "{\"responseLossRate\":1,\"holdMs\":10,\"latencyMs\":1}");
        confirmNewOrder();
        confirmNewOrder();
        fault("CONFIRM", "{\"serverErrorRate\":1}");
        confirmNewOrder();

        mockMvc.perform(delete("/fake/admin/faults")).andExpect(status().isNoContent());
        mockMvc.perform(get("/fake/admin/faults/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['TOSS.CONFIRM'].requests").value(3))
                .andExpect(jsonPath("$['TOSS.CONFIRM'].latencyApplied").value(2))
                .andExpect(jsonPath("$['TOSS.CONFIRM'].responseLosses").value(2))
                .andExpect(jsonPath("$['TOSS.CONFIRM'].serverErrors").value(1))
                .andExpect(jsonPath("$['TOSS.CONFIRM'].tampered").value(0));

        mockMvc.perform(delete("/fake/admin/faults/stats")).andExpect(status().isNoContent());
        mockMvc.perform(get("/fake/admin/faults/stats"))
                .andExpect(jsonPath("$['TOSS.CONFIRM']").doesNotExist());
    }

    @Test
    @DisplayName("같은 seed로 다시 시작하면 같은 순서의 확률 판단이 같은 결과를 낸다")
    void sameSeedReproducesDecisions() throws Exception {
        fault("CONFIRM", "{\"responseLossRate\":0.5}");

        mockMvc.perform(put("/fake/admin/faults/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(42));
        List<Boolean> first = decisions(30);

        mockMvc.perform(put("/fake/admin/faults/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seed\":42}"));
        List<Boolean> second = decisions(30);

        assertThat(second).isEqualTo(first);
        assertThat(first).contains(true, false);
        mockMvc.perform(get("/fake/admin/faults/seed"))
                .andExpect(jsonPath("$.seed").value(42));
    }

    private List<Boolean> decisions(int count) {
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(faults.shouldLoseResponse(PgProvider.TOSS, PgOperation.CONFIRM));
        }
        return result;
    }
}
