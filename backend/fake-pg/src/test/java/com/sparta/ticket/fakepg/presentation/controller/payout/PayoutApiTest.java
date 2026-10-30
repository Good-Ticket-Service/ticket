package com.sparta.ticket.fakepg.presentation.controller.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.domain.repository.PgPayoutRepository;
import com.sparta.ticket.fakepg.support.FakePgIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 모의 PG 지급대행 계약(settlement-design 7.1절, Task 20-6): 비동기 처리·멱등·미등록·실패 코드·장애 주입. */
class PayoutApiTest extends FakePgIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PgPayoutRepository payouts;

    private static String body(String accountRef, long amount) {
        return "{\"sellerRef\":\"seller-1\",\"accountRef\":\"" + accountRef + "\",\"holderMasked\":\"홍*동\",\"amount\":"
                + amount + "}";
    }

    private ResultActions request(String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/payouts")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions inquire(String key) throws Exception {
        return mockMvc.perform(get("/v1/payouts/" + key).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void fault(String operation, String json) throws Exception {
        mockMvc.perform(put("/fake/admin/faults/TOSS/" + operation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private static String key() {
        return "payout:" + UUID.randomUUID() + ":0";
    }

    private static void waitProcessing() throws InterruptedException {
        Thread.sleep(PAYOUT_DELAY_MS + 200);
    }

    @Test
    void acceptedPayoutIsProcessingThenCompleted() throws Exception {
        String key = key();
        JsonNode accepted = json(request(key, body("ACC-1", 43_400)).andExpect(status().isOk()));
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("payoutId").asText()).startsWith("payout_");

        inquire(key).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        waitProcessing();
        inquire(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(43_400))
                .andExpect(jsonPath("$.payoutId").value(accepted.get("payoutId").asText()))
                .andExpect(jsonPath("$.failureCode").doesNotExist());
    }

    @Test
    void invalidAccountFailsWithAccountInvalidOnlyAfterProcessing() throws Exception {
        String key = key();
        request(key, body("INVALID-ACC", 1_000)).andExpect(jsonPath("$.status").value("ACCEPTED"));
        inquire(key).andExpect(jsonPath("$.failureCode").doesNotExist());
        waitProcessing();

        inquire(key)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("ACCOUNT_INVALID"));
    }

    @Test
    void sameKeyReplaysSameResultAndDifferentBodyIsRejected() throws Exception {
        String key = key();
        String payoutId =
                json(request(key, body("ACC-1", 1_000))).get("payoutId").asText();

        request(key, body("ACC-1", 1_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutId").value(payoutId));
        request(key, body("ACC-1", 2_000))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void unknownKeyIsAuthoritativelyNotFound() throws Exception {
        inquire(key())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND_PAYOUT"));
    }

    @Test
    void concurrentSameKeyAcceptsOnePayout() throws Exception {
        String key = key();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return json(request(key, body("ACC-1", 1_000)))
                            .path("payoutId")
                            .asText();
                }));
            }
            start.countDown();
            List<String> payoutIds = new ArrayList<>();
            for (Future<String> result : results) payoutIds.add(result.get(30, TimeUnit.SECONDS));
            assertThat(payoutIds).doesNotContain("").containsOnly(payoutIds.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(payouts.findByIdempotencyKey(key)).isPresent();
    }

    /** 접수는 끝났는데 응답이 유실되면, 같은 키 조회로 접수된 지급을 확인한다. 재요청은 새 지급을 만들지 않는다. */
    @Test
    void lostResponseIsRecoveredByInquiryWithoutSecondPayout() throws Exception {
        fault("PAYOUT", "{\"responseLossRate\":1,\"holdMs\":50}");
        String key = key();
        request(key, body("ACC-1", 1_000)).andExpect(status().isInternalServerError());
        fault("PAYOUT", "{}");

        String payoutId =
                json(inquire(key).andExpect(status().isOk())).get("payoutId").asText();
        request(key, body("ACC-1", 1_000)).andExpect(jsonPath("$.payoutId").value(payoutId));
    }

    @Test
    void injectedRejectionBecomesRejectedFailureAndStaysSame() throws Exception {
        fault("PAYOUT", "{\"failRate\":1}");
        String key = key();
        request(key, body("ACC-1", 1_000)).andExpect(jsonPath("$.status").value("ACCEPTED"));
        fault("PAYOUT", "{}");
        waitProcessing();

        inquire(key)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("REJECTED"));
        inquire(key).andExpect(jsonPath("$.failureCode").value("REJECTED"));
    }

    @Test
    void serverErrorBeforeProcessingDoesNotAccept() throws Exception {
        fault("PAYOUT", "{\"serverErrorRate\":1}");
        String key = key();
        request(key, body("ACC-1", 1_000)).andExpect(status().isInternalServerError());

        inquire(key).andExpect(status().isNotFound());
    }
}
