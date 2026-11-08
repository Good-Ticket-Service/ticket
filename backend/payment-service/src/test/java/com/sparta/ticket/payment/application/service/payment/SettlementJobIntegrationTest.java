package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.service.refund.RefundService;
import com.sparta.ticket.payment.application.service.refund.RefundTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementHoldReason;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementPaymentFlow;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제 확정과 정산 반영의 분리(#304). 정산 쪽 오류가 결제 확정을 막지 않고, 반영은 빠짐없이·한 번만 이뤄지는지 본다.
 * 판매자·회차는 시험마다 나눠 다른 시험 데이터와 섞이지 않게 한다.
 */
class SettlementJobIntegrationTest extends PaymentIntegrationTest {

    private static final AtomicLong SELLERS = new AtomicLong(93_040_000L);

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
    private SettlementItemRepository items;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettlementReconcileRepository reconcile;

    private SettlementPaymentFlow flow;

    @BeforeEach
    void setUp() {
        flow = new SettlementPaymentFlow(
                paymentService, paymentTransactions, payments, jdbc, paymentClock, settlementJobs);
        pg.cancelReturns(request -> new PgResult.Approved(
                "cancel-" + request.idempotencyKey(), request.amount(), LocalDateTime.now(paymentClock)));
    }

    /** 정산 작업을 처리하지 않고 승인만 확정한다. */
    private Payment approvedWithoutSettlement(long sellerId) {
        Payment payment = flow.inProgress(sellerId, sellerId);
        paymentTransactions.complete(
                payment.getId(),
                new PgResult.Approved("key-" + payment.getId(), 45_000, LocalDateTime.now(paymentClock)));
        return payments.findById(payment.getId()).orElseThrow();
    }

    private List<Map<String, Object>> jobs(Payment payment) {
        return jdbc.queryForList(
                "SELECT reason, status, attempts FROM p_settlement_jobs WHERE payment_id = ? ORDER BY reason",
                payment.getId());
    }

    private PaymentStatus status(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow().getStatus();
    }

    private void injectFailure(String name, String table, String condition) {
        jdbc.execute("CREATE OR REPLACE FUNCTION " + name + "() RETURNS trigger AS $$ BEGIN"
                + " IF " + condition + " THEN RAISE EXCEPTION 'injected " + table + " failure'; END IF;"
                + " RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION " + name
                + "()");
    }

    private void removeFailure(String name, String table) {
        jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
    }

    /** 처리된 작업을 다시 대기로 돌린다(같은 작업이 두 번 처리되는 상황). */
    private void requeue(Payment payment) {
        jdbc.update(
                "UPDATE p_settlement_jobs SET status = 'PENDING', done_at = NULL, attempts = 0, next_attempt_at = ?"
                        + " WHERE payment_id = ?",
                LocalDateTime.now(paymentClock),
                payment.getId());
    }

    @Test
    @DisplayName("승인 확정과 정산 작업은 함께 커밋되고, 작업 저장이 실패하면 확정도 함께 롤백된다")
    void jobIsSavedAtomicallyWithApproval() {
        Payment saved = approvedWithoutSettlement(SELLERS.incrementAndGet());
        assertThat(status(saved)).isEqualTo(PaymentStatus.DONE);
        assertThat(jobs(saved)).singleElement().satisfies(job -> {
            assertThat(job.get("reason")).isEqualTo("PAYMENT_APPROVED");
            assertThat(job.get("status")).isEqualTo("PENDING");
        });
        assertThat(items.findSaleByPaymentId(saved.getId())).as("SALE은 워커가 만든다").isEmpty();

        Payment rejected = flow.inProgress(SELLERS.incrementAndGet(), 1L);
        injectFailure("fail_job_for_test", "p_settlement_jobs", "NEW.payment_id = " + rejected.getId());
        try {
            assertThatThrownBy(() -> paymentTransactions.complete(
                            rejected.getId(),
                            new PgResult.Approved("key-" + rejected.getId(), 45_000, LocalDateTime.now(paymentClock))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            removeFailure("fail_job_for_test", "p_settlement_jobs");
        }
        assertThat(status(rejected)).as("작업 없이 확정만 남지 않는다").isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(jobs(rejected)).isEmpty();
    }

    @Test
    @DisplayName("원장 쓰기가 계속 실패해도 결제는 DONE이고 작업만 격리되며, 원인을 고친 뒤 재처리하면 한 번 반영된다")
    void ledgerFailureIsolatesJobAndConvergesAfterFix() {
        long seller = SELLERS.incrementAndGet();
        injectFailure("fail_ledger_for_test", "p_ledger_transactions", "NEW.seller_id = " + seller);
        Payment payment;
        try {
            payment = approvedWithoutSettlement(seller);
            assertThat(status(payment)).as("정산 오류가 결제 확정을 막지 않는다").isEqualTo(PaymentStatus.DONE);

            for (int i = 0; i < 5; i++) {
                settlementJobs.drain();
                paymentClock.advance(Duration.ofMinutes(11));
            }
            assertThat(jobs(payment)).singleElement().satisfies(job -> {
                assertThat(job.get("status")).isEqualTo("BLOCKED");
                assertThat(job.get("attempts")).isEqualTo(5);
            });
            assertThat(items.findSaleByPaymentId(payment.getId()))
                    .as("부분 반영 없음")
                    .isEmpty();
            assertThat(flow.ledgerTransactions(payment.getId())).isZero();
        } finally {
            removeFailure("fail_ledger_for_test", "p_ledger_transactions");
        }

        requeue(payment);
        settlementJobs.drain();

        assertThat(jobs(payment))
                .singleElement()
                .satisfies(job -> assertThat(job.get("status")).isEqualTo("DONE"));
        assertThat(items.findSaleByPaymentId(payment.getId()))
                .get()
                .extracting(SettlementItem::getStatus)
                .isEqualTo(SettlementItemStatus.PENDING);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(1);
        assertThat(status(payment)).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("SALE이 만들어지기 전에 환불을 접수하면 SALE이 보류(HELD)로 생겨 지급 대상에서 빠진다")
    void refundAcceptedBeforeSaleCreatesHeldSale() {
        Payment payment = approvedWithoutSettlement(SELLERS.incrementAndGet());

        refundTransactions.accept(payment.getOrderId(), RefundReason.USER_CANCEL);
        assertThat(items.findSaleByPaymentId(payment.getId()))
                .as("접수 시점에는 SALE이 없다")
                .isEmpty();
        settlementJobs.drain();

        SettlementItem sale = items.findSaleByPaymentId(payment.getId()).orElseThrow();
        assertThat(sale.getStatus()).isEqualTo(SettlementItemStatus.HELD);
        assertThat(sale.getHoldReason()).isEqualTo(SettlementHoldReason.REFUND_IN_PROGRESS);
        assertThat(flow.ledgerTransactions(payment.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("환불 완료 작업이 승인 작업과 함께 쌓여도 SALE·분개가 순서와 무관하게 맞고, 다시 처리해도 한 번만 반영된다")
    void refundCompletedBeforeSaleConvergesOnceInAnyOrder() {
        Payment payment = approvedWithoutSettlement(SELLERS.incrementAndGet());
        assertThat(refundService
                        .refund(payment.getOrderId(), RefundReason.USER_CANCEL)
                        .refundStatus())
                .isEqualTo("DONE");
        assertThat(jobs(payment))
                .extracting(job -> job.get("reason"))
                .containsExactly("PAYMENT_APPROVED", "REFUND_COMPLETED");

        settlementJobs.drain();

        assertThat(items.findSaleByPaymentId(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementItemStatus.CANCELED);
        assertThat(flow.ledgerTransactions(payment.getId())).as("판매·반대 분개").isEqualTo(2);
        assertThat(flow.balances(payment.getId()))
                .allSatisfy((account, net) -> assertThat(net).as(account).isZero());

        requeue(payment);
        settlementJobs.drain();

        assertThat(jobs(payment))
                .allSatisfy(job -> assertThat(job.get("status")).isEqualTo("DONE"));
        assertThat(flow.ledgerTransactions(payment.getId())).as("다시 처리해도 그대로").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_settlement_items WHERE payment_id = ?", Integer.class, payment.getId()))
                .isEqualTo(1);
    }

    private long saleMissing() {
        return reconcile.runChecks(null).stream()
                .filter(result -> result.check().equals("SALE_MISSING"))
                .findFirst()
                .orElseThrow()
                .violations();
    }

    @Test
    @DisplayName("반영 대기 중인 결제는 정산 대조에서 판매 누락으로 세지 않고, 격리되면 센다")
    void reconcileIgnoresPendingJobButFlagsBlocked() {
        long before = saleMissing();
        Payment payment = approvedWithoutSettlement(SELLERS.incrementAndGet());

        assertThat(saleMissing()).as("대기 작업은 누락이 아니다").isEqualTo(before);

        jdbc.update("UPDATE p_settlement_jobs SET status = 'BLOCKED' WHERE payment_id = ?", payment.getId());
        assertThat(saleMissing()).as("격리 작업은 누락으로 드러난다").isEqualTo(before + 1);
    }
}
