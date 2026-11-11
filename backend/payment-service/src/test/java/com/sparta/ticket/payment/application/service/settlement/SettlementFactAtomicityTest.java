package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SettlementFactAtomicityTest extends PaymentIntegrationTest {
    @Autowired
    PaymentService service;

    @Autowired
    PaymentTransactions transactions;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sourceFailureRollsBackSettlementButPreservesCompletedPaymentAndCanRetry() {
        long seller = 980001;
        var flow = new SettlementPaymentFlow(service, transactions, payments, jdbc, paymentClock, settlementJobs);
        jdbc.execute(
                "CREATE FUNCTION p_projection_test_reject() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.seller_id=980001 THEN RAISE EXCEPTION ''test source failure'' USING ERRCODE=''23514''; END IF; RETURN NEW; END'");
        jdbc.execute(
                "CREATE TRIGGER projection_test_reject BEFORE INSERT ON p_settlement_projection_facts FOR EACH ROW EXECUTE FUNCTION p_projection_test_reject()");
        Long paymentId = null;
        try {
            var payment = flow.approvedFor(seller, 980002);
            paymentId = payment.getId();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(flow.ledgerTransactions(paymentId)).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_projection_facts WHERE payment_id=?",
                            Long.class,
                            paymentId))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_items WHERE payment_id=? AND kind='SALE'",
                            Long.class,
                            paymentId))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM p_settlement_jobs WHERE payment_id=?", String.class, paymentId))
                    .isEqualTo("PENDING");
        } finally {
            jdbc.execute("DROP TRIGGER projection_test_reject ON p_settlement_projection_facts");
            jdbc.execute("DROP FUNCTION p_projection_test_reject()");
        }
        flow.requeueSettlement(paymentId);
        settlementJobs.drain();
        settlementJobs.drain();
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(flow.ledgerTransactions(paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_projection_facts WHERE payment_id=?", Long.class, paymentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM p_settlement_jobs WHERE payment_id=?", String.class, paymentId))
                .isEqualTo("DONE");
    }
}
