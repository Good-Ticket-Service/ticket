package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentSettlementBackfillJob;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementFailure;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 포착이 빠진 결제와 계산 실패 SALE의 보충(Task 20-4, settlement-design 4.4절, 시험 S2·S3). */
class SettlementBackfillIntegrationTest extends PaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private RefundService refundService;

    @Autowired
    private PaymentSettlementBackfillJob job;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private SettlementItemRepository items;

    @Autowired
    private JdbcTemplate jdbc;

    private SettlementPaymentFlow flow;

    @BeforeEach
    void setUp() {
        flow = new SettlementPaymentFlow(paymentService, paymentTransactions, payments, jdbc, paymentClock);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
    }

    private SettlementItem sale(Payment payment) {
        return items.findSaleByPaymentId(payment.getId()).orElseThrow();
    }

    @Test
    void missingSaleIsBackfilledWithSaleEntry() {
        Payment payment = flow.approved();
        flow.dropSettlement(payment.getId());

        job.runOnce();

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(1);
    }

    @Test
    void refundedPaymentWithoutSaleIsPostedAndReversedTogether() {
        Payment payment = flow.approved();
        refundService.refund(payment.getOrderId(), RefundReason.USER_CANCEL);
        flow.dropSettlement(payment.getId());
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);

        job.runOnce();

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(2);
        assertThat(flow.balances(payment.getId()))
                .allSatisfy((account, net) -> assertThat(net).as(account).isZero());
    }

    /** 계속 실패하는 낮은 ID 결제가 있어도 뒤 결제는 같은 실행에서 메워지고, 원인을 고치면 실패 결제도 메워진다. */
    @Test
    void failingSaleDoesNotBlockOthersAndRecoversAfterFix() {
        Payment failing = flow.approved(LocalDateTime.of(2025, 12, 31, 23, 0));
        Payment missing = flow.approved();
        flow.dropSettlement(missing.getId());

        job.runOnce();

        assertThat(sale(failing).getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(sale(failing).getFailureReason()).isEqualTo(SettlementFailure.POLICY_NOT_FOUND);
        assertThat(sale(missing).getStatus()).isEqualTo(SettlementItemStatus.PENDING);

        jdbc.update(
                "UPDATE p_payments SET approved_at = ? WHERE id = ?", LocalDateTime.now(paymentClock), failing.getId());
        job.runOnce();

        assertThat(sale(failing).getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(sale(failing).getFailureReason()).isNull();
        assertThat(flow.ledgerTransactions(failing.getId())).isEqualTo(1);
    }

    @Test
    void concurrentRunsBackfillOnce() throws Exception {
        Payment payment = flow.approved();
        flow.dropSettlement(payment.getId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = List.of(
                    pool.submit(() -> {
                        start.await();
                        job.runOnce();
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await();
                        job.runOnce();
                        return null;
                    }));
            start.countDown();
            for (Future<?> run : runs) run.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(1);
    }
}
