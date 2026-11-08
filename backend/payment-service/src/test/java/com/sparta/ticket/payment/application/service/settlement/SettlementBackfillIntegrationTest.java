package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentSettlementBackfillJob;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.application.service.payment.SettlementJobWorker;
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

    @Autowired
    private SettlementJobWorker worker;

    @BeforeEach
    void setUp() {
        flow = new SettlementPaymentFlow(
                paymentService, paymentTransactions, payments, jdbc, paymentClock, settlementJobs);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
    }

    private SettlementItem sale(Payment payment) {
        return items.findSaleByPaymentId(payment.getId()).orElseThrow();
    }

    /** 정산 반영 작업이 처리되기 전이면 SALE이 없고, 워커가 판매 분개까지 만든다(#304: 포착 누락은 작업이 메운다). */
    @Test
    void missingSaleIsBackfilledWithSaleEntry() {
        Payment payment = flow.approved();
        flow.dropSettlement(payment.getId());
        flow.requeueSettlement(payment.getId());

        settlementJobs.drain();

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(1);
    }

    @Test
    void refundedPaymentWithoutSaleIsPostedAndReversedTogether() {
        Payment payment = flow.approved();
        refundService.refund(payment.getOrderId(), RefundReason.USER_CANCEL);
        flow.dropSettlement(payment.getId());
        flow.requeueSettlement(payment.getId());
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);

        settlementJobs.drain();

        assertThat(sale(payment).getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(2);
        assertThat(flow.balances(payment.getId()))
                .allSatisfy((account, net) -> assertThat(net).as(account).isZero());
    }

    /** 보충 잡은 계산 실패(UNPOSTED) SALE만 다시 계산한다. 원인을 고치면 같은 SALE이 판매 분개와 함께 정상이 된다. */
    @Test
    void failingSaleDoesNotBlockOthersAndRecoversAfterFix() {
        Payment failing = flow.approved(LocalDateTime.of(2025, 12, 31, 23, 0));

        job.runOnce();

        assertThat(sale(failing).getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(sale(failing).getFailureReason()).isEqualTo(SettlementFailure.POLICY_NOT_FOUND);

        jdbc.update(
                "UPDATE p_payments SET approved_at = ? WHERE id = ?", LocalDateTime.now(paymentClock), failing.getId());
        job.runOnce();

        assertThat(sale(failing).getStatus()).isEqualTo(SettlementItemStatus.PENDING);
        assertThat(sale(failing).getFailureReason()).isNull();
        assertThat(flow.ledgerTransactions(failing.getId())).isEqualTo(1);
    }

    /** 워커 두 개가 같은 작업을 동시에 노려도 SKIP LOCKED·점유로 한 번만 반영된다. */
    @Test
    void concurrentRunsBackfillOnce() throws Exception {
        Payment payment = flow.approved();
        flow.dropSettlement(payment.getId());
        flow.requeueSettlement(payment.getId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = List.of(
                    pool.submit(() -> {
                        start.await();
                        worker.runOnce();
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await();
                        worker.runOnce();
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
