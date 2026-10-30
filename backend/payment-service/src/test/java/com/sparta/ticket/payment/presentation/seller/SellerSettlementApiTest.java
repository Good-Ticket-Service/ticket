package com.sparta.ticket.payment.presentation.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.settlement.SettlementStatementTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 판매자 수취 계좌·정산 조회 API(Task 20-7, settlement-design 7.4절, 시험 S7·S11). */
class SellerSettlementApiTest extends PaymentIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);
    private static final AtomicLong SELLERS = new AtomicLong(110_000);
    private static final AtomicLong SCHEDULES = new AtomicLong(120_000);
    private static final String ACCOUNT =
            "{\"bankCode\":\"004\",\"accountNumber\":\"123-456-789012\",\"holderName\":\"홍길동\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundService refundService;

    @Autowired
    private SettlementStatementTransactions statements;

    @Autowired
    private JdbcTemplate jdbc;

    private SettlementPaymentFlow flow;
    private long seller;

    @BeforeEach
    void setUp() {
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
        seller = SELLERS.incrementAndGet();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, String role) {
        return request.header(UserHeaders.USER_ID, String.valueOf(userId))
                .header(UserHeaders.USERNAME, "seller@ticket.com")
                .header(UserHeaders.ROLE, role);
    }

    private ResultActions putAccount(String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = as(put("/api/seller/payout-account"), seller, "SELLER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (ifMatch != null) request.header(HttpHeaders.IF_MATCH, ifMatch);
        return mockMvc.perform(request);
    }

    private ResultActions getAs(String uri, long userId) throws Exception {
        return mockMvc.perform(as(get(uri), userId, "SELLER"));
    }

    private long issuedStatementId(long scheduleId) {
        return jdbc.queryForObject(
                "SELECT id FROM p_settlement_statements WHERE seller_id = ? AND schedule_id = ?",
                Long.class,
                seller,
                scheduleId);
    }

    @Test
    void accountIsRegisteredOnceThenChangedOnlyWithCurrentVersion() throws Exception {
        // 발행이 먼저 만든 기준 행(계좌 없음)은 등록 전이다
        long schedule = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);
        getAs("/api/seller/payout-account", seller)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYOUT_ACCOUNT_NOT_FOUND"));

        String etag = putAccount(null, ACCOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bankCode").value("004"))
                .andExpect(jsonPath("$.data.accountMasked").value("****9012"))
                .andExpect(jsonPath("$.data.holderMasked").value("홍*동"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
        assertThat(etag).matches("\"\\d+\"");
        getAs("/api/seller/payout-account", seller)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etag));

        putAccount(null, ACCOUNT)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        putAccount("\"999\"", ACCOUNT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        putAccount(etag, ACCOUNT.replace("789012", "780000"))
                .andExpect(jsonPath("$.data.accountMasked").value("****0000"));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_seller_payout_accounts WHERE seller_id = ?", Integer.class, seller))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT account_ref || account_masked || holder_masked FROM p_seller_payout_accounts"
                                + " WHERE seller_id = ?",
                        String.class,
                        seller))
                .as("계좌번호·예금주 원문은 저장하지 않는다")
                .doesNotContain("780000", "123-456", "홍길동");
    }

    @Test
    void onlySellerRoleCanUseSellerSettlementApi() throws Exception {
        mockMvc.perform(as(get("/api/seller/settlements"), seller, "USER")).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/seller/payout-account"), seller, "MASTER")).andExpect(status().isForbidden());
    }

    /** 1회차 발행 → 환불로 차감 43,400 → 2회차 발행에서 전액 상계(NO_PAYOUT). 목록·상세·미회수가 그 흐름을 그대로 보여 준다. */
    @Test
    void sellerSeesOwnStatementsLinesAndReceivable() throws Exception {
        long first = SCHEDULES.incrementAndGet();
        Payment refunded = flow.approvedFor(seller, first);
        flow.approvedFor(seller, first);
        statements.issue(seller, first, CUTOFF);
        refundService.refund(refunded.getOrderId(), RefundReason.SCHEDULE_CANCELED);
        getAs("/api/seller/settlements/receivable", seller)
                .andExpect(jsonPath("$.data.receivable").value(43_400));

        long second = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, second);
        statements.issue(seller, second, CUTOFF);

        getAs("/api/seller/settlements?size=1", seller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1));
        getAs("/api/seller/settlements/" + issuedStatementId(first), seller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payableAmount").value(86_800))
                .andExpect(jsonPath("$.data.payoutStatus").value("READY"))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].sellerAmount").value(43_400));
        getAs("/api/seller/settlements/" + issuedStatementId(second), seller)
                .andExpect(jsonPath("$.data.status").value("NO_PAYOUT"))
                .andExpect(jsonPath("$.data.payoutStatus").doesNotExist())
                .andExpect(jsonPath("$.data.offsetTotal").value(43_400))
                .andExpect(jsonPath("$.data.lines[?(@.kind == 'DEDUCTION')].amount")
                        .value(43_400));
        getAs("/api/seller/settlements/receivable", seller)
                .andExpect(jsonPath("$.data.receivable").value(0))
                .andExpect(jsonPath("$.data.oldestAt").doesNotExist());
    }

    @Test
    void otherSellersStatementLooksMissing() throws Exception {
        long schedule = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, schedule);
        statements.issue(seller, schedule, CUTOFF);

        getAs("/api/seller/settlements/" + issuedStatementId(schedule), seller + 100_000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STATEMENT_NOT_FOUND"));
    }

    /** 정산서 발행이 기준 행을 만드는 것과 최초 등록이 동시에 일어나도 판매자 행은 하나이고 계좌 정보는 남는다. */
    @Test
    void firstRegistrationRacingIssuanceKeepsOneRowAndAccount() throws Exception {
        long schedule = SCHEDULES.incrementAndGet();
        flow.approvedFor(seller, schedule);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> issue = pool.submit(() -> {
                start.await();
                return statements.issue(seller, schedule, CUTOFF);
            });
            Future<Integer> register = pool.submit(() -> {
                start.await();
                return putAccount(null, ACCOUNT).andReturn().getResponse().getStatus();
            });
            start.countDown();
            issue.get(30, TimeUnit.SECONDS);
            assertThat(register.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_seller_payout_accounts WHERE seller_id = ? AND account_ref IS NOT NULL",
                        Integer.class,
                        seller))
                .isEqualTo(1);
    }
}
