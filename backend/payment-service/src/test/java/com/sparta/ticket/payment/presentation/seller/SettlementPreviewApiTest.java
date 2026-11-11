package com.sparta.ticket.payment.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionConsumerJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementSourceJdbc;
import com.sparta.ticket.payment.support.LedgerTestFixture;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SettlementPreviewApiTest extends PaymentIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProjectionCatalogJdbc catalog;

    @Autowired
    ProjectionConsumerJdbc consumer;

    @Autowired
    SettlementSourceJdbc source;

    @Autowired
    PaymentService paymentService;

    @Autowired
    PaymentTransactions paymentsTx;

    @Autowired
    PaymentRepository payments;

    @BeforeEach
    void initialize() {
        resetBuilds();
        LedgerTestFixture.clear(jdbc);
    }

    @AfterEach
    void cleanup() {
        resetBuilds();
    }

    private void resetBuilds() {
        jdbc.execute("TRUNCATE p_settlement_projection_builds CASCADE");
        jdbc.update("INSERT INTO p_settlement_projection_head(id) VALUES(1)");
    }

    @Test
    void initializationOwnershipAndRangeValidation() throws Exception {
        LocalDate day = LocalDate.now(paymentClock);
        String uri = "/api/seller/settlements/preview?from=" + day + "&to=" + day;
        mvc.perform(get(uri)).andExpect(status().isUnauthorized());
        mvc.perform(as(get(uri), 3, "USER")).andExpect(status().isForbidden());
        mvc.perform(as(get(uri), 3, "SELLER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("INITIALIZING"))
                .andExpect(jsonPath("$.data.totals").doesNotExist());
        mvc.perform(as(get(uri + "&scheduleId=0"), 3, "SELLER")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/seller/settlements/preview?from=" + day + "&to=" + day.plusDays(31)), 3, "SELLER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void realPaymentSourceIsVisibleOnlyForItsAuthenticatedSellerAfterVerifiedActivation() throws Exception {
        long seller = 970001;
        var flow = new SettlementPaymentFlow(paymentService, paymentsTx, payments, jdbc, paymentClock, settlementJobs);
        var payment = flow.approvedFor(seller, 970002);
        long expected = jdbc.queryForObject(
                "SELECT seller_amount FROM p_settlement_items WHERE payment_id=? AND kind='SALE'",
                Long.class,
                payment.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_facts WHERE payment_id=?",
                        Long.class,
                        payment.getId()))
                .isEqualTo(1);
        UUID build = UUID.randomUUID();
        catalog.create(build, "B", "test", "API visibility");
        catalog.materialize(build);
        catalog.fanout(build);
        consumer.apply(build, source.facts(""));
        catalog.claim(1000, 30000).forEach(catalog::published);
        catalog.freeze(build);
        assertThat(catalog.verify(build)).isTrue();
        catalog.activate(build, 0);
        LocalDate day = LocalDate.now(paymentClock);
        String uri = "/api/seller/settlements/preview?from=" + day + "&to=" + day;
        mvc.perform(as(get(uri), seller, "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generation").value(1))
                .andExpect(jsonPath("$.data.totals.saleCount").value(1))
                .andExpect(jsonPath("$.data.totals.netSellerAmount").value(expected));
        mvc.perform(as(get(uri), seller + 1, "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.saleCount").value(0))
                .andExpect(jsonPath("$.data.days.totalElements").value(0));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long user, String role) {
        return request.header(UserHeaders.USER_ID, Long.toString(user))
                .header(UserHeaders.USERNAME, "projection-test")
                .header(UserHeaders.ROLE, role);
    }
}
