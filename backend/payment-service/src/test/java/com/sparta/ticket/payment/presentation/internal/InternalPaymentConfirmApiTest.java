package com.sparta.ticket.payment.presentation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class InternalPaymentConfirmApiTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(5_000_000);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentService service;

    private String ready() {
        String order = "api-confirm-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                50_000,
                5_000,
                LocalDateTime.of(2099, 1, 1, 0, 0)));
        return order;
    }

    @Test
    void successAndLostResponseReplayHaveRawContractWithoutToken() throws Exception {
        String order = ready();
        for (int i = 0; i < 2; i++) {
            String body = mvc.perform(post("/internal/payments/{orderId}/confirm", order)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pgAuthToken\":\"api-secret\",\"amount\":45000}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentId").isNumber())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body).doesNotContain("api-secret", "pgAuthToken");
        }
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void failureAndUnknownAreBusinessResultsInHttp200() throws Exception {
        pg.approveReturns(request -> new PgResult.Rejected("CARD_DECLINED", "거절"));
        mvc.perform(post("/internal/payments/{orderId}/confirm", ready())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pgAuthToken\":\"reject-key\",\"amount\":45000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CARD_DECLINED"));
        pg.approveReturns(request -> new PgResult.Unknown("timeout"));
        mvc.perform(post("/internal/payments/{orderId}/confirm", ready())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pgAuthToken\":\"unknown-key\",\"amount\":45000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void amountMismatchAndMissingPaymentUseContractErrors() throws Exception {
        mvc.perform(post("/internal/payments/{orderId}/confirm", ready())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pgAuthToken\":\"key\",\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        mvc.perform(post("/internal/payments/{orderId}/confirm", "missing-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pgAuthToken\":\"key\",\"amount\":45000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        assertThat(pg.approveCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"pgAuthToken\":null,\"amount\":45000}",
                "{\"pgAuthToken\":\" \",\"amount\":45000}",
                "{\"pgAuthToken\":\"key\"}",
                "{\"pgAuthToken\":\"key\",\"amount\":2147483648}"
            })
    void invalidBodyNeverCallsPg(String body) throws Exception {
        mvc.perform(post("/internal/payments/{orderId}/confirm", ready())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        assertThat(pg.approveCalls()).isZero();
    }
}
