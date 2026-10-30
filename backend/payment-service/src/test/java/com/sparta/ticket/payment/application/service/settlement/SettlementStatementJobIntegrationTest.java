package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 발행 잡: cutoff까지 정산 가능해진 (판매자, 회차)를 모두 발행하고, 다시 돌려도 중복 발행하지 않는다(Task 20-5, 시험 S4). */
class SettlementStatementJobIntegrationTest extends PaymentIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 4, 0);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private SettlementStatementJob job;

    @Autowired
    private JdbcTemplate jdbc;

    private int statementsOf(long sellerId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_statements WHERE seller_id = ?", Integer.class, sellerId);
    }

    @Test
    void issuesEveryDuePairOnceAcrossRuns() {
        SettlementPaymentFlow flow =
                new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        flow.approvedFor(70_001L, 80_001L);
        flow.approvedFor(70_001L, 80_002L);
        flow.approvedFor(70_002L, 80_001L);

        job.run(CUTOFF);
        job.run(CUTOFF);

        assertThat(statementsOf(70_001L)).isEqualTo(2);
        assertThat(statementsOf(70_002L)).isEqualTo(1);
    }

    @Test
    void doesNotIssueBeforeEligible() {
        SettlementPaymentFlow flow =
                new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        flow.approvedFor(70_003L, 80_003L);

        // 정산 가능일 2026-11-11 00:00 전
        job.run(LocalDateTime.of(2026, 11, 10, 4, 0));

        assertThat(statementsOf(70_003L)).isZero();
    }
}
