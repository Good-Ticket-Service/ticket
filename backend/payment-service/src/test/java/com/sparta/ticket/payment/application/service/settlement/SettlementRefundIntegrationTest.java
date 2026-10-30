package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.refund.RefundTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementHoldReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 환불 접수·완료의 정산 반영(Task 20-4, settlement-design 4.3절, 시험 S2·S5·S8). */
class SettlementRefundIntegrationTest extends PaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private RefundService refundService;

    @Autowired
    private RefundTransactions refundTransactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private SettlementItemRepository items;

    @Autowired
    private JdbcTemplate jdbc;

    private SettlementPaymentFlow flow;

    @BeforeEach
    void setUp() {
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(
                request -> new PgResult.Approved("cancel-" + request.idempotencyKey(), request.amount(), now()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(paymentClock);
    }

    private SettlementItem sale(Payment payment) {
        return items.findSaleByPaymentId(payment.getId()).orElseThrow();
    }

    private Long refundId(Payment payment) {
        return refunds.findByPaymentId(payment.getId()).orElseThrow().getId();
    }

    @Test
    void acceptHoldsSaleWithoutTouchingLedger() {
        Payment payment = flow.approved();

        refundTransactions.accept(payment.getOrderId(), RefundReason.USER_CANCEL);

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.HELD);
        assertThat(sale(payment).getHoldReason()).isEqualTo(SettlementHoldReason.REFUND_IN_PROGRESS);
        assertThat(sale(payment).getRefundId()).isEqualTo(refundId(payment));
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(1);
    }

    @Test
    void completionBeforeStatementReversesSaleOnce() {
        Payment payment = flow.approved();

        assertThat(refundService
                        .refund(payment.getOrderId(), RefundReason.USER_CANCEL)
                        .refundStatus())
                .isEqualTo("DONE");
        refundService.refund(payment.getOrderId(), RefundReason.USER_CANCEL);

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        assertThat(flow.ledgerTransactions(payment.getId())).as("판매·반대 분개").isEqualTo(2);
        assertThat(flow.balances(payment.getId()))
                .isNotEmpty()
                .allSatisfy((account, net) -> assertThat(net).as(account).isZero());
    }

    /** 정산서는 Task 20-5에서 생기므로 SALE을 SQL로 정산서에 편입된 상태로 만든다. */
    @Test
    void completionAfterStatementCreatesFullDeduction() {
        Payment payment = flow.approved();
        jdbc.update(
                "UPDATE p_settlement_items SET status = 'ON_STATEMENT', statement_id = 1 WHERE payment_id = ?"
                        + " AND kind = 'SALE'",
                payment.getId());

        refundService.refund(payment.getOrderId(), RefundReason.SCHEDULE_CANCELED);

        SettlementItem deduction =
                items.findDeductionByRefundId(refundId(payment)).orElseThrow();
        assertThat(deduction.getStatus()).isEqualTo(SettlementItemStatus.OPEN);
        assertThat(deduction.getPrincipal()).isEqualTo(43_400L);
        assertThat(deduction.getRemaining()).isEqualTo(43_400L);
        assertThat(deduction.getOriginItemId()).isEqualTo(sale(payment).getId());
        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.ON_STATEMENT);
        assertThat(flow.balances(payment.getId()))
                .as("판매자 채무는 정산서 몫으로 남고, 같은 금액의 채권이 생긴다")
                .containsEntry("SELLER_PAYABLE", -43_400L)
                .containsEntry("SELLER_RECEIVABLE", 43_400L);
    }

    @Test
    void completionOfUnpostedSaleKeepsRefundForBackfill() {
        Payment payment = flow.approved(LocalDateTime.of(2025, 12, 31, 23, 0));

        refundService.refund(payment.getOrderId(), RefundReason.USER_CANCEL);

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(sale(payment).getRefundId()).isEqualTo(refundId(payment));
        assertThat(flow.ledgerTransactions(payment.getId())).isZero();
    }
}
